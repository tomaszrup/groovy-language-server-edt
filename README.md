# Language Support for Groovy

[![CI](https://github.com/tomaszrup/groovy-language-server-edt/actions/workflows/ci.yml/badge.svg)](https://github.com/tomaszrup/groovy-language-server-edt/actions/workflows/ci.yml)
[![GLS Coverage](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/tomaszrup/groovy-language-server-edt/badges/.badges/gls-coverage.json)](https://github.com/tomaszrup/groovy-language-server-edt)

A [Language Server Protocol](https://microsoft.github.io/language-server-protocol/) implementation for [Groovy](http://groovy-lang.org/), powered by [Eclipse JDT](https://www.eclipse.org/jdt/) and [groovy-eclipse](https://github.com/groovy/groovy-eclipse).

The language server runs as a headless [Eclipse/Equinox](https://www.eclipse.org/equinox/) application, reusing the same Groovy compiler and type inference engine that powers the Eclipse IDE's Groovy support — giving you accurate completions, diagnostics, and navigation backed by a real compiler.

## Features

The following LSP capabilities are supported:

- **Completion** — context-aware suggestions for methods, properties, variables, and types (triggers: `.`, ` `, `@`), with resolve support
- **Definition** — go-to-definition for classes, methods, and variables (including into external library source JARs)
- **Type Definition** — navigate to the type declaration of a symbol
- **Hover** — inline documentation and type information on hover
- **References** — find all references to a symbol across the workspace
- **Implementation** — find all implementations of an interface or abstract class
- **Rename** — safe rename refactoring with prepare support
- **Signature Help** — parameter hints when calling methods and constructors (triggers: `(`, `,`)
- **Document Symbols** — outline view of classes, methods, and fields in a file
- **Workspace Symbols** — search for symbols across the entire workspace
- **Code Actions** — quick-fix suggestions, organize imports, add missing imports, remove unused imports
- **Code Lens** — reference counts on types, methods, and fields
- **Diagnostics** — real-time syntax and compilation error/warning reporting, including unused import and unused declaration detection
- **Inlay Hints** — inline type annotations for `def` variables, parameter name hints, closure parameter types, and method return types
- **Semantic Highlighting** — semantic-aware syntax highlighting with custom `typeKeyword` token type for `class`, `interface`, `enum`, `trait` keywords
- **Document Highlight** — highlights all occurrences of a symbol in the current file
- **Formatting** — document formatting (full, range, and on-type for `}`, `;`, `\n`) using a bundled IntelliJ-style default profile, with optional Eclipse formatter XML profile override support
- **Folding Ranges** — code folding for classes, methods, imports, and region markers
- **Type Hierarchy** — explore supertypes and subtypes of a class or interface
- **Call Hierarchy** — explore incoming and outgoing calls for a method
- **File Rename** — automatic import updates when `.groovy` or `.java` files are renamed

### VS Code Extension

A bundled VS Code extension provides a seamless editor experience:

- **Automatic server lifecycle** — the language server starts automatically when a `.groovy` file is opened or a workspace contains `.groovy` files
- **Red Hat Java integration** — the extension works alongside the [Language Support for Java™ by Red Hat](https://marketplace.visualstudio.com/items?itemName=redhat.java) extension to resolve project classpaths automatically
- **Classpath resolution** — multi-strategy classpath detection: Java extension API → `getProjectSettings` fallback → Gradle wrapper fallback (runs `gradlew` with an init script)
- **Go-to-definition into external libraries** — navigating to classes from JARs opens a decompiled/source view via the `groovy-source://` virtual document provider
- **Status bar indicator** — shows server state (Waiting for Java, Starting, Importing, Compiling, Ready, Error) with live progress
- **Restart command** — `Groovy: Restart Language Server` command to restart the server without reloading the window
- **Show Output Channel** — `Groovy: Show Output Channel` command to view the language server log
- **Clean workspace on start** — Eclipse workspace data is wiped on every launch to avoid stale metadata

### Language Configuration

The extension ships a `language-configuration.json` that enables rich editing support for Groovy files out of the box:

- **Comment toggling** — `Ctrl+/` toggles `//` line comments; `Shift+Alt+A` wraps selections in `/* */` block comments
- **Bracket matching & auto-closing** — automatically closes `()`, `[]`, `{}`, `""`, `''`, and backticks; matching brackets are highlighted
- **Doc-comment continuation** — pressing Enter inside a `/** */` block automatically inserts ` * ` on the next line
- **Smart indentation** — increases indent after `{`, `class`, `interface`, `enum`, `trait`, `if`, `for`, `while`, etc.; decreases indent on `}` or `]`
- **Folding markers** — `// region` / `// endregion` (and `<editor-fold>`) markers create foldable regions in the editor
- **Surrounding pairs** — selecting text and typing a bracket or quote character wraps the selection

### Configuration

| Option | Type | Description |
|---|---|---|
| `groovy.java.home` | `string` | Path to a JDK installation (requires JDK 21+). Falls back to `JAVA_HOME` if not set |
| `groovy.ls.vmargs` | `string` | Extra JVM arguments for the language server (default: `"-Xmx1G"`) |
| `groovy.trace.server` | `string` | Traces communication between VS Code and the server. Values: `off` (default), `messages`, `verbose` |
| `groovy.format.enabled` | `boolean` | Enable or disable document formatting (default: `true`) |
| `groovy.format.settingsUrl` | `string` | Path to an Eclipse formatter XML profile. Supports absolute paths, workspace-relative paths, and `file://` URIs. If unset, the bundled IntelliJ-style default profile is used |
| `groovy.inlayHints.variableTypes.enabled` | `boolean` | Show inferred type hints for variables declared with `def` (default: `true`) |
| `groovy.inlayHints.parameterNames.enabled` | `boolean` | Show parameter name hints at method and constructor call sites (default: `true`) |
| `groovy.inlayHints.closureParameterTypes.enabled` | `boolean` | Show inferred types for closure parameters (default: `true`) |
| `groovy.inlayHints.methodReturnTypes.enabled` | `boolean` | Show inferred return type hints for methods declared with `def` (default: `true`) |

### Eclipse Formatter XML

By default, the formatter uses the bundled IntelliJ-style profile. You can override it by pointing to an Eclipse formatter XML profile:

```json
{
  "groovy.format.settingsUrl": ".settings/eclipse-formatter.xml"
}
```

This is the same XML format exported by Eclipse IDE's **Java > Code Style > Formatter** preferences. Relative paths are resolved from the workspace root.

## Prerequisites

- **JDK 21** or later
- [Language Support for Java™ by Red Hat](https://marketplace.visualstudio.com/items?itemName=redhat.java) VS Code extension — required for classpath resolution

## Architecture

The language server is built on the [Eclipse JDT](https://www.eclipse.org/jdt/) compiler infrastructure with [groovy-eclipse](https://github.com/groovy/groovy-eclipse) patches that add Groovy support to the Java compiler. This is the same compiler stack used by the Eclipse IDE's Groovy-Eclipse plugin.

Key components:
- **Eclipse/Equinox OSGi runtime** — the server runs as a headless Equinox application
- **groovy-eclipse patched JDT** — replaces standard Eclipse JDT with Groovy-aware variants (`org.eclipse.jdt.core`, `org.eclipse.jdt.groovy.core`, `org.codehaus.groovy`)
- **LSP4J** — provides the LSP protocol binding
- **Groovy 5.0** — targets the latest Groovy major version

## Build

Requires JDK 21+.

```sh
./gradlew build
```

### Build the VS Code extension

```sh
# Build the server product and package the .vsix
./gradlew packageVsix
```

This:
1. Compiles the language server core
2. Assembles the Equinox product (downloads groovy-eclipse bundles, collects plugins)
3. Copies the server into `vscode-extension/server/`
4. Runs `npm install` and `webpack` in the extension directory
5. Packages the `.vsix` file

The server communicates via standard I/O using the Language Server Protocol.

## Provenance & Credits

This project is a clean-room reimplementation of a Groovy language server, built on top of Eclipse JDT and groovy-eclipse. It was started from an initial scaffold in [groovy-language-server/groovy-language-server](https://github.com/groovy-language-server/groovy-language-server) and then rewritten to use the Eclipse compiler infrastructure for accurate type inference, completion, and navigation.

The previous iteration of this project ([tomaszrup/groovy-language-server](https://github.com/tomaszrup/groovy-language-server)) used a custom Groovy compiler pipeline. This version replaces it entirely with Eclipse JDT + groovy-eclipse for a more robust foundation.

### References

- [Language Support for Java™ by Red Hat](https://marketplace.visualstudio.com/items?itemName=redhat.java) — VS Code extension providing Java language support and classpath resolution via the Java extension API
- [Eclipse JDT (Java Development Tools)](https://www.eclipse.org/jdt/) — the compiler infrastructure and language tooling framework that powers this language server
- [Prominic's Groovy Language Server](https://github.com/GroovyLanguageServer/groovy-language-server) — an earlier Groovy language server that served as inspiration for this project

## License

Licensed under the [Eclipse Public License - v 2.0](LICENSE).
