<div align="center">

<img src="src/main/resources/META-INF/pluginIcon.svg" width="88" alt="Beanline logo">

# Beanline

**Paste a URL. Land on the Java method behind it.**

An IntelliJ IDEA plugin for Jakarta EE, Java EE and Spring back ends.

[![Build](https://github.com/AzizARSLN/beanLine/actions/workflows/build.yml/badge.svg)](https://github.com/AzizARSLN/beanLine/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![IntelliJ IDEA 2025.3+](https://img.shields.io/badge/IntelliJ%20IDEA-2025.3%2B-blue.svg)](https://www.jetbrains.com/idea/)
[![GitHub stars](https://img.shields.io/github/stars/AzizARSLN/beanLine?style=flat)](https://github.com/AzizARSLN/beanLine/stargazers)

**English** | [Türkçe](README.tr.md)

</div>

Large Java back ends make simple questions slow: which method serves this URL, who injects this bean, and why did
this `@Transactional` do nothing? Beanline answers them inside the IDE.

If Beanline saves you time, a ⭐ helps other Java developers find it.

## Features

### Find an endpoint from any URL

Paste a URL from the browser's Network tab, a log line or a bug report into the **Endpoints** tab of Search
Everywhere, and jump to the Java method that serves it.

![Illustration: a URL in the Endpoints tab of Search Everywhere listing the matching methods](docs/images/url-search.svg)

- Hosts, context paths and query strings are ignored.
- Path parameters match any value. `${id}`, `{{id}}` and `:id` in the text you search for work as wildcards.
- Start with a verb to narrow the results, as in `POST /api/orders`.
- Endpoints inherited from base classes are found under the path of the concrete class.

**Navigate > Go to Endpoint** (`Ctrl+Alt+Shift+E`) searches for the selected text, the string at the caret or a
URL on the clipboard. The string at the caret can be in any file, so it also works from front-end code.

### Navigate between beans and injection points

A gutter icon on a bean class lists where it is injected. A gutter icon on an injected field lists the beans that
can satisfy it.

![Illustration: a gutter icon on a bean class opening a list of its injection points](docs/images/bean-navigation.svg)

### Catch container pitfalls while you type

![Illustration: a warning that @Transactional does not apply to a call from the same bean](docs/images/self-invocation.svg)

| Inspection | What it reports |
|---|---|
| **Self-invocation bypasses the bean proxy** | A call inside the same bean to a method whose `@Transactional`, `@TransactionAttribute`, `@Asynchronous`, `@Async`, cache or retry annotation will not apply |
| **Interception annotation on a method the proxy cannot intercept** | The same annotations on `private`, `final` or `static` methods |
| **Managed bean created with new** | `new` on a bean with injected fields, which stay `null` |

Each inspection can be turned off or have its severity changed under **Settings > Editor > Inspections > Beanline**.

The images on this page are illustrations of the plugin's features, not screenshots.

## Supported

- IntelliJ IDEA 2025.3 or newer
- `javax.*` and `jakarta.*` EJB, CDI and JAX-RS
- Spring stereotypes, `@Autowired` and Spring MVC mappings

## Limitations

- Only Java sources are analyzed. Kotlin beans and controllers are not detected yet.
- Custom stereotype annotations, such as your own annotation meta-annotated with `@Service`, are not recognized.
- Paths built at runtime cannot be resolved. Conditional beans (`@Profile`, `@Conditional`) are treated as present.
- JAX-RS `@ApplicationPath` and Spring `server.servlet.context-path` are not read. URLs match by their ending instead.

## Install

Download `beanline-<version>.zip` from the [latest release](https://github.com/AzizARSLN/beanLine/releases/latest).
Do not unzip it. Install it with **Settings > Plugins > ⚙ > Install Plugin from Disk**.

## Build from source

Requires JDK 21.

```shell
./gradlew buildPlugin
```

The plugin ZIP is written to `build/distributions`. To try it in a sandbox IDE, run `./gradlew runIde`.

## Development

```shell
./gradlew test
```

## Roadmap

- Kotlin support through UAST
- Impact analysis from a bean method up to the endpoints that reach it
- Circular dependency detection and a bean graph
- Team rules stored in the repository

## Contributing

Bug reports and pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[MIT](LICENSE)
