/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package viper.carbon

import viper.silver.frontend.{SilFrontend, SilFrontendConfig}
import viper.silver.verifier.{Success => SilSuccess, Failure => SilFailure}

/**
 * The main object for Carbon containing the execution start-point.
 */
object Carbon extends CarbonFrontend {
  def main(args: Array[String]) {
    execute(args)

    sys.exit(result match {
      case SilSuccess => 0
      case SilFailure(errors) => 1
    })
  }
}

class CarbonFrontend extends SilFrontend {
  private var carbonInstance: CarbonVerifier = _

  def createVerifier(fullCmd: String) = {
    carbonInstance = CarbonVerifier(Seq("Arguments: " -> fullCmd))

    carbonInstance
  }

  def configureVerifier(args: Seq[String]) = {
  	carbonInstance.parseCommandLine(args)

    carbonInstance.config
  }
}

class CarbonConfig(args: Seq[String]) extends SilFrontendConfig(args, "Carbon") {
  val boogieProverLog = opt[String]("proverLog",
    descr = "Prover log file written by Boogie (default: none)",
    default = None,
    noshort = true
  )

  val boogieOut = opt[String]("print",
    descr = "Write the Boogie output file to the provided filename (default: none)",
    default = None,
    noshort = true
  )
  
  val boogieOpt = opt[String]("boogieOpt",
  descr = "Option(s) to pass-through as options to Boogie (changing the output generated by Boogie is not supported) (default: none)",
  default = None,
  noshort = true
  )

  val boogieExecutable = opt[String]("boogieExe",
    descr = "Manually-specified full path to Boogie.exe executable (default: ${BOOGIE_EXE})",
    default = None,
    noshort = true
  )

  val Z3executable = opt[String]("z3Exe",
    descr = "Manually-specified full path to Z3.exe executable (default: ${Z3_EXE})",
    default = None,
    noshort = true
  )

}