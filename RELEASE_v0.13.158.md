# Agent v0.13.158

Agent is the independent Android app name. This release refreshes the A store icon,
puts model selection and offline file import first, and groups downloads, online
providers, response settings and advanced runtime controls separately.

Local model imports preserve the original file, reject duplicate concurrent imports,
and clean up interrupted copies. Accessibility labels, saved-state restoration and
large-text layouts have been strengthened. LiteRT-LM Android is 0.17.1.

The application ID and historical download filename pattern remain compatible with
existing installations and F-Droid's Binaries URL. They are technical identifiers,
not the displayed product name. Full and Play are separate distributions.

Store artwork and localized text are supplied through the standard Fastlane paths.
F-Droid collects them from a built tagged release; central repository processing is
separate from GitHub publication. Its current explicit Name override requires a
maintainer adjustment before the central listing title can inherit Agent.

Physical-device validation is optional and was not performed. Release status and
accepted source-bound evidence are recorded separately; these notes do not claim
publication before the release workflow succeeds.
