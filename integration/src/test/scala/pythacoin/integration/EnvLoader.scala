package pythacoin.integration

import scala.io.Source

/** Reads a project-root `.env` file and merges it with `sys.env`. The real
  * environment wins on conflict so an operator can patch a single value on
  * the command line without editing the file.
  *
  * Lines starting with `#` are treated as comments. Surrounding single or
  * double quotes on the value are stripped. Lines without `=` are ignored.
  */
object EnvLoader {

    def load(): Map[String, String] = loadFile() ++ sys.env

    /** `.env` only — without the `sys.env` overlay. Used by tests that need
      * to know what the file declared (rather than the merged view).
      */
    def loadFile(path: String = ".env"): Map[String, String] =
        try
            val src = Source.fromFile(path)
            try parseLines(src.getLines())
            finally src.close()
        catch case _: java.io.FileNotFoundException => Map.empty

    private def parseLines(lines: Iterator[String]): Map[String, String] =
        lines
            .map(_.trim)
            .filter(line => line.nonEmpty && !line.startsWith("#") && line.contains('='))
            .map { line =>
                val idx = line.indexOf('=')
                line.substring(0, idx).trim -> stripQuotes(line.substring(idx + 1).trim)
            }
            .toMap

    private def stripQuotes(s: String): String =
        if s.length >= 2 && (s.startsWith("\"") && s.endsWith("\"")
            || s.startsWith("'") && s.endsWith("'"))
        then s.substring(1, s.length - 1)
        else s
}
