import $ivy.`com.lihaoyi::ammonite-ops:1.8.1`
import $ivy.`io.circe::circe-generic-extras:0.13.0`
import $ivy.`io.circe::circe-yaml:0.11.0-M1`

import ammonite.ops._
import cats.syntax.either._
import io.circe._
import io.circe.generic.extras.auto._
import io.circe.generic.extras.Configuration
import io.circe.yaml._
import java.time.LocalDateTime

// This allows default arguments in ADTs: https://stackoverflow.com/a/47644276
implicit val customConfig: Configuration = Configuration.default.withDefaults

case class Config(languages: Seq[Language])

case class Language(id: String, name: String, extension: String, parseTable: ParseTable, sources: Sources, antlrBenchmarks: Seq[ANTLRBenchmark] = Seq.empty) {
    def parseTablePath(implicit suite: Suite) = parseTable.path(this)

    def sourcesDir(implicit suite: Suite) = suite.sourcesDir / id
    
    def measurementsDir(implicit suite: Suite) = suite.measurementsDir / id
    def benchmarksDir(implicit suite: Suite) = suite.benchmarksDir / id

    def sourceFilesBatch(source: Option[String] = None)(implicit suite: Suite) = ls.rec! (source match {
        case Some(id) => sourcesDir / "batch" / id
        case None => sourcesDir / "batch"
    }) |? (_.ext == extension)
    def sourceFilesIncremental(implicit suite: Suite) = ls.rec! sourcesDir / "incremental" |? (_.ext == extension)
    def sourceFilesPerFileBenchmark(implicit suite: Suite): Seq[Path] = {
        val files = sourceFilesBatch() sortBy(-_.size)
        val trimPercentage: Float = 10F
        val filesTrimmed = files.slice(
            ((trimPercentage / 100F) * files.size).toInt,
            (((100F - trimPercentage) / 100F) * files.size).toInt
        )

        val fileCount = filesTrimmed.size
        val step = fileCount / suite.samples

        for (i <- 0 until suite.samples) yield filesTrimmed(i * step)
    }
}

sealed trait ParseTable {
    def path(language: Language)(implicit suite: Suite): Path
}
case class GitSpoofax(repo: String, subDir: String) extends ParseTable {
    def repoDir(language: Language)(implicit suite: Suite) = Suite.languagesDir / language.id
    def spoofaxProjectDir(language: Language)(implicit suite: Suite) = repoDir(language) / RelPath(subDir)
    def path(language: Language)(implicit suite: Suite) = spoofaxProjectDir(language) / "target" / "metaborg" / "sdf.tbl"
}
case class LocalParseTable(file: String) extends ParseTable {
    def path(language: Language)(implicit suite: Suite) = pwd / RelPath(file)
}

object ParseTable {
    implicit val decodeParseTable: Decoder[ParseTable] =
        Decoder[GitSpoofax]     .map[ParseTable](identity) or
        Decoder[LocalParseTable].map[ParseTable](identity)
}

case class Sources(batch: Seq[BatchSource] = Seq.empty, incremental: Seq[IncrementalSource] = Seq.empty)

sealed trait Source {
    def id: String
}
sealed trait RepoSource extends Source {
    def repo: String
}
sealed trait LocalSource extends Source {
    def path: String
}

sealed trait BatchSource extends Source
case class BatchRepoSource(id: String, repo: String) extends BatchSource with RepoSource
case class BatchLocalSource(id: String, path: String) extends BatchSource with LocalSource

object BatchSource {
    implicit val decodeBatchSource: Decoder[BatchSource] =
        Decoder[BatchRepoSource] .map[BatchSource](identity) or
        Decoder[BatchLocalSource].map[BatchSource](identity)
}

case class IncrementalSource(id: String, repo: String,
        fetchOptions: Seq[String] = Seq.empty, files: Seq[String] = Seq.empty, versions: Int = -1) extends RepoSource

case class ANTLRBenchmark(id: String, benchmark: String)

case class Suite(configPath: Path, languages: Seq[Language], dir: Path, iterations: Int, samples: Int, spoofaxDir: Path, reportDir: Path) {
    def languagesDir    = dir / 'languages
    def sourcesDir      = dir / 'sources
    def measurementsDir = dir / 'measurements
    def benchmarksDir   = dir / 'benchmarks
    def resultsDir      = dir / 'results
    def reportsDir      = dir / 'reports
    def websiteDir      = dir / 'website
}

object Suite {

    implicit val suite = {
        val dir        = sys.env.get("JSGLR2EVALUATION_WORKING_DIR").map(getPath).getOrElse(throw new IllegalArgumentException("missing 'JSGLR2EVALUATION_WORKING_DIR' environment variable"))
        val spoofaxDir = sys.env.get("JSGLR2EVALUATION_SPOOFAX_DIR").map(getPath).getOrElse(pwd / up / up / up)
        val reportDir  = sys.env.get("JSGLR2EVALUATION_REPORT_DIR").map(getPath).getOrElse(dir / "reports")

        val configPath = {
            val filename = RelPath(sys.env.get("CONFIG").getOrElse("config.yml"))

            if (exists! (dir / filename))
                dir / filename
            else
                pwd / filename
        }
        val configJson = parser.parse(read! configPath)
        val config = configJson.flatMap(_.as[Config]).valueOr(throw _)

        val iterations = sys.env.get("ITERATIONS").map(_.toInt).getOrElse(1)
        val samples    = sys.env.get("SAMPLES").map(_.toInt).getOrElse(1)

        Suite(configPath, config.languages, dir, iterations, samples, spoofaxDir, reportDir)
    }

    implicit def languagesDir    = suite.languagesDir
    implicit def sourcesDir      = suite.sourcesDir
    implicit def measurementsDir = suite.measurementsDir
    implicit def benchmarksDir   = suite.benchmarksDir
    implicit def resultsDir      = suite.resultsDir
    implicit def reportsDir      = suite.reportsDir
    implicit def websiteDir      = suite.websiteDir
    
    implicit def parseTableMeasurementsPath = resultsDir / "measurements-parsetable.csv"
    implicit def parsingMeasurementsPath    = resultsDir / "measurements-parsing.csv"

    implicit def batchBenchmarksPath             = resultsDir / "benchmarks-batch-time.csv"
    implicit def batchBenchmarksNormalizedPath   = resultsDir / "benchmarks-batch-throughput.csv"
    implicit def perFileBenchmarksPath           = resultsDir / "benchmarks-perFile-time.csv"
    implicit def perFileBenchmarksNormalizedPath = resultsDir / "benchmarks-perFile-throughput.csv"

    implicit def incrementalResultsDir = resultsDir / "incremental"

}

def getPath(path: String) =
    if (path.startsWith("~/"))
        Path(System.getProperty("user.home") + path.substring(1))
    else if (path.startsWith("./"))
        pwd / RelPath(path.substring(2))
    else if (path.startsWith(".."))
        pwd / RelPath(path)
    else
        Path(path)

def timed(name: String)(block: => Unit)(implicit suite: Suite): Unit = {
    println(s"$name: start @ ${LocalDateTime.now}")
    val t0 = System.currentTimeMillis()
    
    block
    val t1 = System.currentTimeMillis()

    val seconds = (BigDecimal(t1 - t0)) / 1000

    val report =
        s"$name: finished in " +
        (if (seconds < 60)
            s"${round(seconds, 1)}s"
        else if (seconds < 3600)
            s"${round(seconds / 60, 1)}m"
        else
            s"${round(seconds / 3600, 1)}h")

    println(report)

    write.append(suite.dir / "timing.txt", s"${LocalDateTime.now} $report\n")
}

case class CSV(columns: Seq[String], rows: Seq[CSVRow])
case class CSVRow(values: Map[String, String]) {
    def apply(column: String) = values.get(column).getOrElse("")
}

object CSV {
    // Source: https://stackoverflow.com/a/13336039
    private val commaRegex = """,(?=([^\"]*\"[^\"]*\")*[^\"]*$)"""

    private def stripQuotes(s: String) =
        if (s.startsWith("\"") && s.endsWith("\"")) s.substring(1, s.length - 1) else s

    private def parseLine(line: String) =
        line.split(commaRegex).map(stripQuotes)

    def parse(file: Path): CSV = {
        read.lines(file) match {
            case headerLine +: rowLines =>
                val columns = parseLine(headerLine).toSeq

                val rows = rowLines.map { rowLine =>
                    CSVRow((columns zip parseLine(rowLine)).toMap)
                }

                CSV(columns, rows)
        }
    }

}

import scala.math.BigDecimal.RoundingMode

def round(number: BigDecimal, scale: Int = 0): BigDecimal = number.setScale(scale, RoundingMode.HALF_UP)
def round(number: String): String = if (number != "NaN" && number != "") round(BigDecimal(number)).toString else number
