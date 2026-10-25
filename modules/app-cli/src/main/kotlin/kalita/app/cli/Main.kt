// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import java.io.PrintStream
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(run(args.toList(), System.out, System.err))
}

/**
 * Entry point for the `kalita` CLI (dev_plan.md §12.12/§12.17, FR-AG1): one channel over
 * `modules:agent-api`'s tools, shared by shell scripts and any agent with a terminal. `run` takes
 * the output streams explicitly (mirrors `modules:cli`'s `devcli` entry point) so tests can capture
 * them instead of going through `System.out`/`System.exit`.
 */
fun run(args: List<String>, out: PrintStream, err: PrintStream): Int {
    val root = RootCommand(err).subcommands(
        ContextCommand(out, err),
        ResolveCommand(out, err),
        OpCommand().subcommands(
            OpListCommand(out, err),
            OpAddCommand(out, err),
            OpUpdateCommand(out, err),
            OpDeleteCommand(out, err),
            OpRestoreCommand(out, err),
        ),
        BalanceCommand(out, err),
        InboxCommand().subcommands(InboxListCommand(out, err), InboxApproveCommand(out, err), InboxRejectCommand(out, err)),
        DictCommand().subcommands(DictAddCommand(out, err)),
        AgentCommand().subcommands(AgentListCommand(out, err), AgentAddCommand(out, err), AgentRemoveCommand(out, err)),
        SchemaCommand(out),
        McpCommand(err),
    )
    return try {
        root.parse(args)
        0
    } catch (e: ProgramResult) {
        e.statusCode
    } catch (e: PrintMessage) {
        (if (e.printError) err else out).println(e.message)
        if (e.printError) 2 else 0
    } catch (e: UsageError) {
        err.println(e.message)
        2
    } catch (e: CliktError) {
        err.println(e.message)
        2
    } catch (e: Exception) {
        err.println("error: ${e.message ?: e.javaClass.simpleName}")
        2
    }
}
