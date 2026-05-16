package io.github.memo33.dbpfcli

import caseapp.{RemainingArgs, ArgsName, HelpMessage, ExtraName, ValueDescription}
import caseapp.core.util.fansi
import com.dynatrace.hash4j.hashing.Hashing
import io.github.memo33.scdbpf.{DbpfFile, DbpfEntry, Tgi, DbpfPackager, strategy}, strategy.throwExceptions
import io.github.memo33.scdbpf.compat.Input


object Util {
  def formatFile(file: java.io.File | java.nio.file.Path): fansi.Str = fansi.Color.Cyan(file.toString)
}
import Util.formatFile

@ArgsName("DBPF input files")
@HelpMessage(s"""
  |Concatenate multiple DBPF files into one.
  |
  |The files are taken in the order in which they are specified on the command line.
  |Later files overwrite TGIs of earlier files, but if there are duplicate TGIs within a single file, that is an error by default.
  |
  |Using the --append option allows patching an existing DBPF file in place.
  |
  |Examples:
  |  dbpf-concat -o output.dat input1.dat input2.dat
  |  dbpf-concat -o file.dat --append input1.dat input2.dat
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
}

@ArgsName("DBPF input file")
@HelpMessage(s"""
  |Convert a DBPF file to a human-readable text format.
  |
  |This is especially useful for comparing DBPF files with git.
  |
  |Examples:
  |  dbpf-text input.dat > output.txt
  |
  |Directory files are ignored.
  |The hash algorithm used is rapidhashV3.
  |""".stripMargin.trim)
final case class TextConvOptions(
  @HelpMessage("Unpack QFS-compressed entries. This is much slower, but avoids hash differences that are only caused by the QFS compression.")
  decompressed: Boolean = false,
  @HelpMessage("Sort entries by TGI instead of keeping the original order.")
  sorted: Boolean = false,
)

case object TextConvCommand extends caseapp.Command[TextConvOptions] {
  override def names = List(List("text"))

  def run(options: TextConvOptions, args: RemainingArgs): Unit = {
    if (args.all.size != 1) {
      error(caseapp.core.Error.Other(if (args.all.isEmpty) s"No input file specified." else s"Multiple input files specified. Pass exactly one input file."))
    }
    val path = java.nio.file.Path.of(args.all.head)
    if (!java.nio.file.Files.exists(path)) {
      error(caseapp.core.Error.Other(s"Input file not found: ${formatFile(path)}"))
    }
    val dbpf = DbpfFile.read(path.toFile)
    val entries = if (options.sorted) dbpf.entries.sortBy(_.tgi) else dbpf.entries
    val hasher = if (options.decompressed) DecompressingDbpfHasher() else FastDbpfHasher()
    scala.util.Using.resource(new java.io.BufferedWriter(new java.io.OutputStreamWriter(System.out, java.nio.charset.StandardCharsets.UTF_8))) { out =>
      for (e <- entries if e.tgi != Tgi.Directory) {
        val hash = hasher.hashDbpfEntry(e)
        out.write(f"${e.tgi}, H:$hash%016x, ${e.tgi.label}%n")
      }
    }
  }
}

trait DbpfHasher {
  /* for single-threaded use only */
  def hashDbpfEntry(entry: DbpfEntry): Long
}
class FastDbpfHasher() extends DbpfHasher {
  private val buffer = new Array[Byte](65536)
  private val hashStream = Hashing.rapidhashV3.hashStream()
  def hashDbpfEntry(entry: DbpfEntry): Long = {
    hashStream.reset()
    scala.util.Using.resource(entry.input()) { in =>
      var read = 0
      while ({ read = in.readBlock(buffer); read != -1 }) {
        hashStream.putBytes(buffer, 0, read): Unit
      }
    }
    hashStream.getAsLong()
  }
}
class DecompressingDbpfHasher() extends DbpfHasher {
  private val hashStream = Hashing.rapidhashV3.hashStream()
  def hashDbpfEntry(entry: DbpfEntry): Long = {
    hashStream.reset()
    val arr = scala.util.Using.resource(entry.input())(Input.slurpBytes(_))
    hashStream.putBytes(DbpfPackager.decompress(arr))
    hashStream.getAsLong()
  }
}

object Main extends caseapp.core.app.CommandsEntryPoint {

  val commands = Seq(
    ConcatCommand,
    TextConvCommand,
  )

  val progName = "dbpf"
}
