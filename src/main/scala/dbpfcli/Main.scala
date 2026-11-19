package io.github.memo33.dbpfcli

import scala.jdk.CollectionConverters.*
import caseapp.{RemainingArgs, ArgsName, HelpMessage, ExtraName, ValueDescription}
import caseapp.core.util.fansi
import com.dynatrace.hash4j.hashing.Hashing
import io.github.memo33.scdbpf.{DbpfFile, DbpfEntry, BufferedEntry, RawType, Tgi, DbpfPackager, strategy}, strategy.throwExceptions
import io.github.memo33.scdbpf.compat.{Input, ByteInput, ByteArrayInput}
import io.github.memo33.passera.unsigned.UInt


object Util {
  def formatFile(file: java.io.File | java.nio.file.Path): fansi.Str = fansi.Color.Cyan(file.toString)

  lazy val sourceDateEpochOpt: Option[UInt] = {
    Option(System.getenv("SOURCE_DATE_EPOCH")).filter(_.nonEmpty)
      .flatMap(s => implicitly[Numeric[Long]].parseString(s))
      .map(l => UInt(l.toInt))
  }
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
      error(caseapp.core.Error.Other(s"Error: No input files specified."))
    }
    val paths = args.all.map(f => java.nio.file.Path.of(f))
    val missing = paths.filterNot(p => java.nio.file.Files.exists(p))
    if (missing.nonEmpty) {
      error(caseapp.core.Error.Other(s"Error: Input file(s) not found: ${missing.map(formatFile).mkString(", ")}"))
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

    DbpfFile.write(
      file = outputPath.toFile,
      entries = entriesToWrite,
      dateCreated = Util.sourceDateEpochOpt,
      dateModified = Util.sourceDateEpochOpt,
    ): Unit
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

@ArgsName("DBPF output file [input files]")
@HelpMessage(s"""
  |Insert individual files into a DBPF file.
  |
  |Each input file must have a .TGI sidecar file (Reader's format). Optionally, you can pass this .TGI file as input argument.
  |
  |The DBPF file may be specified as the first positional argument or with -o/--output.
  |
  |If the DBPF file already exists, the input files are inserted into the existing file; otherwise it is created.
  |Existing TGIs will be overwritten.
  |
  |Examples:
  |  dbpf-import dbpf.dat input1.ini input2.lua.TGI input3.rul
  |  dbpf-import -o dbpf.dat input1.xml.TGI input2.png input3.eqzt
  |""".stripMargin.trim)
final case class ImportOptions(
  @ExtraName("o") @ValueDescription("file") @HelpMessage("DBPF file")
  output: String = "",
  @HelpMessage("Suppress non-error output.")
  silent: Boolean = false,
)

case object ImportCommand extends caseapp.Command[ImportOptions] {

  private val maxQfsThreshold: Long = 0xffffff

  def run(options: ImportOptions, args: RemainingArgs): Unit = {
    val (outputValue, inputFiles) =
      if (options.output.nonEmpty) {
        (options.output, args.all)
      } else if (args.all.isEmpty) {
        error(caseapp.core.Error.Other("Error: No output DBPF file specified."))
      } else {
        (args.all.head, args.all.tail)
      }
    if (inputFiles.isEmpty) {
      error(caseapp.core.Error.Other(if (options.output.isEmpty) "Error: No output DBPF file or no input files specified." else "Error: No input files specified."))
    }

    val outputPath = java.nio.file.Path.of(outputValue)
    val inputPaths =
      inputFiles.map { s =>
        val normalized = if (s.toUpperCase(java.util.Locale.ENGLISH).endsWith(".TGI")) s.substring(0, s.length - ".TGI".length) else s
        java.nio.file.Path.of(normalized)
      }.distinct
    val missing = inputPaths.filterNot(java.nio.file.Files.isRegularFile(_))
    if (missing.nonEmpty) {
      error(caseapp.core.Error.Other(s"Error: Input file(s) not found or not regular files: ${missing.map(formatFile).mkString(", ")}"))
    }

    val (badInputs, goodInputs) = inputPaths.partitionMap(p => readTgi(p).map(p -> _))
    if (badInputs.nonEmpty) {
      error(caseapp.core.Error.Other(f"Error:%n" + badInputs.mkString(f"%n")))
    }
    val importedTgis = collection.mutable.Map.empty[Tgi, java.nio.file.Path]
    for ((path, tgi) <- goodInputs) {
      if (importedTgis.contains(tgi)) {
        error(caseapp.core.Error.Other(s"Error: Duplicate TGI $tgi among input files: ${formatFile(path)}, ${formatFile(importedTgis(tgi))}"))
      }
      importedTgis(tgi) = path
    }

    val origDbpf = Option.when(java.nio.file.Files.exists(outputPath))(DbpfFile.read(outputPath.toFile))
    val existingEntriesIter: Iterator[DbpfEntry] =
      origDbpf.map(_.entries).getOrElse(Nil).iterator
        .filter(e => e.tgi != Tgi.Directory && !importedTgis.contains(e.tgi))

    val importedEntriesIter: Iterator[DbpfEntry] = goodInputs.iterator.map { (path, tgi0) =>
      new DbpfEntry {
        val tgi = tgi0
        def input(): Input[Byte] = {
          val size = java.nio.file.Files.size(path)
          val (result, compressedSize) =
            if (size <= maxQfsThreshold) {
              val bytes = DbpfPackager.compress(java.nio.file.Files.readAllBytes(path))
              (new ByteArrayInput(bytes), Some(bytes.length))
            } else {
              (new ByteInput(java.nio.file.Files.newInputStream(path)), None)
            }
          if (!options.silent) {
            val mode = if (origDbpf.exists(_.tgiMap.contains(tgi0))) "updating" else "  adding"
            val ratio = compressedSize.map(cs => f"deflated ${100f * (1f - (cs.toFloat / size.toFloat))}%.0f%%").getOrElse("stored 0%")
            println(s"$mode $tgi0  ${formatFile(path)} ($ratio)")
          }
          result
        }
      }
    }

    DbpfFile.write(
      file = outputPath.toFile,
      entries = existingEntriesIter ++ importedEntriesIter,
      dateCreated = origDbpf.map(_.header.dateCreated).orElse(Util.sourceDateEpochOpt),
      dateModified = Util.sourceDateEpochOpt,
    ): Unit
  }

  private def readTgi(path: java.nio.file.Path): Either[String, Tgi] = {
    def parseId(line: String, sidecar: java.nio.file.Path): Either[String, Int] = {
      val hex = if (line.regionMatches(true, 0, "0x", 0, 2)) line.substring(2) else line
      util.Try(java.lang.Integer.parseUnsignedInt(hex, 16))
        .toEither
        .left.map(ex => s"Invalid TGI file ${formatFile(sidecar)}: '$line' is not up to 8 hex characters")
    }
    for {
      sidecar  <- findTgiSidecar(path).toRight(s"TGI file not found for ${formatFile(path)}")
      lines    =  java.nio.file.Files.readAllLines(sidecar)
                    .asScala.map(_.trim).filter(_.nonEmpty).toSeq
      _        <- Option.when(lines.size != 3)(s"Invalid TGI file ${formatFile(sidecar)}: expected exactly 3 non-empty lines").toLeft(())
      tid      <- parseId(lines(0), sidecar)
      gid      <- parseId(lines(1), sidecar)
      iid      <- parseId(lines(2), sidecar)
    } yield Tgi(tid, gid, iid)
  }

  private def findTgiSidecar(path: java.nio.file.Path): Option[java.nio.file.Path] = {
    Iterator(".TGI", ".tgi")
      .map(suffix => path.resolveSibling(path.getFileName.toString + suffix))
      .find(java.nio.file.Files.isRegularFile(_))
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
      error(caseapp.core.Error.Other(if (args.all.isEmpty) s"Error: No input file specified." else s"Error: Multiple input files specified. Pass exactly one input file."))
    }
    val path = java.nio.file.Path.of(args.all.head)
    if (!java.nio.file.Files.exists(path)) {
      error(caseapp.core.Error.Other(s"Error: Input file not found: ${formatFile(path)}"))
    }
    val dbpf = DbpfFile.read(path.toFile)
    val entries = if (options.sorted) dbpf.entries.sortBy(_.tgi) else dbpf.entries
    val hasher = if (options.decompressed) DecompressingDbpfHasher() else FastDbpfHasher()
    val hadError =
      util.boundary {
        scala.util.Using.resource(new java.io.PrintWriter(System.out)) { out =>
          var count = 0
          for (e <- entries if e.tgi != Tgi.Directory) {
            val hash = hasher.hashDbpfEntry(e)
            out.write(f"${e.tgi}, H:$hash%016x, ${e.tgi.label}%n")
            count += 1
            if ((count & 0x3ff) == 0 && out.checkError()) {  // every 1024 steps, flush and check for e.g. SIGPIPE/EPIPE when a pipe to a pager like `less` quits
              // System.err.println("broken pipe?")
              util.boundary.break(true)  // allows resource cleanup
            }
          }
          false
        }
      }
    if (hadError) {
      exit(1)
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
    ImportCommand,
    TextConvCommand,
  )

  val progName = "dbpf"
}
