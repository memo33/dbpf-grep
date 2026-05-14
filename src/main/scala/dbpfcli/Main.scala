package io.github.memo33.dbpfcli

import caseapp.{RemainingArgs, ArgsName, HelpMessage, ExtraName, ValueDescription}
import caseapp.core.util.fansi
import io.github.memo33.scdbpf.{DbpfFile, DbpfEntry, Tgi, strategy}, strategy.throwExceptions


@ArgsName("DBPF input files")
@HelpMessage(s"""
  |Concatenate multiple DBPF files into one.
  |
  |The files are taken in the order in which they are specified on the command line.
  |Later files overwrite TGIs of earlier files, but if there are duplicate TGIs within a single file, that is an error by default.
  |
  |Examples:
  |  dbpf-concat -o output.dat input1.dat input2.dat
  |  dbpf-concat -o file.dat --append input1.dat input2.dat
  |
  |The output file can be the same as one of the input files.
  |""".stripMargin.trim)
final case class ConcatOptions(
  @ExtraName("o") @ValueDescription("file") @HelpMessage("Output file")
  output: String,
  @HelpMessage("If the output file already exists, append to it instead of overwriting it.")
  append: Boolean = false,
  @HelpMessage("If an input file contains duplicate TGIs, keep only the first occurence instead of raising an error.")
  discardDuplicateTgis: Boolean = false,
  @HelpMessage("Suppress non-error output.")
  silent: Boolean = false,
)

case object ConcatCommand extends caseapp.Command[ConcatOptions] {

  def run(options: ConcatOptions, args: RemainingArgs): Unit = {
    if (args.all.isEmpty) {
      error(caseapp.core.Error.Other(s"No input files specified."))
    }
    val paths = args.all.map(f => java.nio.file.Path.of(f))
    val missing = paths.filterNot(p => java.nio.file.Files.exists(p))
    if (missing.nonEmpty) {
      error(caseapp.core.Error.Other(s"Input file(s) not found: ${missing.map(formatFile).mkString(", ")}"))
    }
    val outputPath = java.nio.file.Path.of(options.output)

    val origOpt =
      Option.when(options.append && java.nio.file.Files.exists(outputPath)) {
        DbpfFile.read(outputPath.toFile)
      }
    val dbpfs: Seq[DbpfFile] = origOpt.toSeq ++ paths.map(p => DbpfFile.read(p.toFile))

    val seen = scala.collection.mutable.Set.empty[Tgi]
    val intermediate =
      dbpfs
        .reverse
        .map { d =>
          val uniqueEntriesDir = d.entries.distinctBy(_.tgi)
          val numDiscarded = d.entries.size - uniqueEntriesDir.size
          if (numDiscarded > 0 && !options.discardDuplicateTgis) {
            error(buildDuplicateTgiError(dbpfs))
          }
          val uniqueEntriesNoDir = uniqueEntriesDir.filter(_.tgi != Tgi.Directory)
          val entriesToKeep = uniqueEntriesNoDir.filter(e => !seen.contains(e.tgi))
          seen ++= entriesToKeep.map(_.tgi)
          val numRead = entriesToKeep.size
          val numOverwritten = uniqueEntriesNoDir.size - numRead
          (d, entriesToKeep, numDiscarded, numOverwritten, numRead)
        }
        .reverse

    val padWith = intermediate.map { case (_, _, _, _, numRead) => numRead }.maxOption.getOrElse(0).toString.length

    val entriesToWrite: Iterator[DbpfEntry] =
      intermediate.iterator
        .map { case (d: DbpfFile, entries: Seq[DbpfEntry], numDiscarded: Int, numOverwritten: Int, numRead: Int) =>
          if (!options.silent) {
            val hints = collection.mutable.Buffer.empty[String]
            if (numOverwritten > 0) hints += s"$numOverwritten overwritten"
            if (numDiscarded > 0 && options.discardDuplicateTgis) hints += s"$numDiscarded duplicates"
            val hintsMsg =
              if (hints.nonEmpty) fansi.Color.DarkGray(s" (discarded TGIs: ${hints.mkString(", ")})") else ""
            val count = fansi.Bold.On(String.format("%" + padWith + "d", numRead))
            println(s"""Read ${count} entries$hintsMsg from file ${formatFile(d.file)}""")
          }
          entries
        }
        .flatten

    val _ = DbpfFile.write(file = outputPath.toFile, entries = entriesToWrite)
  }

  private def buildDuplicateTgiError(dbpfs: Iterable[DbpfFile]): caseapp.core.Error = {
    val lines = Seq.newBuilder[String]
    for (d <- dbpfs) {
      val duplicates: Iterable[Tgi] = d.entries.groupBy(_.tgi).filter(_._2.size > 1).map(_._1)
      if (duplicates.nonEmpty) {
        lines += f"""Duplicate TGIs contained in ${formatFile(d.file)}:%n  ${duplicates.mkString(f"%n  ")}"""
      }
    }
    lines += "Error: Duplicate TGIs found in input files."
    caseapp.core.Error.Other(lines.result().mkString(f"%n"))
  }

  private def formatFile(file: java.io.File | java.nio.file.Path): fansi.Str = {
    fansi.Color.Cyan(file.toString)
  }
}

object Main extends caseapp.core.app.CommandsEntryPoint {

  val commands = Seq(
    ConcatCommand,
  )

  val progName = "dbpf"
}
