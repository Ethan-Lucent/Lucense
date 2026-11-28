
# Contributing to Lucense

First off, thank you for considering contributing to Lucense.

Lucense is an offline-first, cross-platform music player built with Kotlin. Contributions of all kinds are welcome, including bug fixes, performance improvements, platform compatibility, accessibility improvements, documentation, and new features.

The goal is to keep Lucense reliable, privacy-conscious, maintainable, and consistent across platforms.

## Before You Start

- Check existing issues and pull requests before starting work.
- For significant changes, open an issue first to discuss the proposed approach.
- Keep changes focused and avoid unrelated modifications.
- Respect the project's offline-first philosophy.
- Do not introduce mandatory accounts, API keys, subscriptions, or unnecessary telemetry.
- Avoid adding dependencies unless they provide meaningful value.

## Development Environment

Lucense requires:

- JDK 17 or newer.
- Git.
- Android SDK for Android development.
- .NET SDK for building the Windows installer.
- Gradle (the project includes its Gradle build configuration).

Refer to the README for platform-specific setup instructions.

## Project Structure

The project is divided into several components:

- `shared/` — Platform-independent Kotlin logic, providers, recognition, parsing, and shared functionality.
- `app/` — Android application, Jetpack Compose, Media3, and Room.
- `desktop/` — Desktop application and Windows-specific functionality.
- `installer/` — Windows installer.
- `tools/` — Build scripts and development utilities.
- `.github/workflows/` — CI and automated build workflows.

Whenever possible, shared functionality should live in `shared/` rather than being independently reimplemented for each platform.

## Building and Testing

Fetch the required tools:

```bash
bash tools/fetch-tools.sh
```

On Linux:

```bash
bash tools/fetch-tools.sh linux
```

Run desktop tests:

```bash
bash tools/build.sh :desktop:test
```

Build Android:

```bash
bash tools/build.sh :app:assembleRelease
```

Run Android instrumented tests:

```bash
bash tools/build.sh :app:connectedDebugAndroidTest
```

For Linux desktop packaging:

```bash
gradle :desktop:packageDeb :desktop:packageRpm :desktop:createDistributable
```

Test your changes before submitting a pull request whenever reasonably possible.

## Coding Guidelines

- Follow the existing Kotlin coding conventions.
- Prefer readable, maintainable code over unnecessary abstractions.
- Keep functions and classes focused on their responsibilities.
- Avoid duplicated platform-independent logic.
- Handle errors explicitly and provide useful user-facing messages.
- Do not silently swallow exceptions.
- Avoid blocking the UI thread.
- Consider memory usage and performance, especially when processing audio.
- Preserve existing user data and backward compatibility wherever possible.
- Keep platform-specific behavior isolated from shared code.

## Privacy and Security

Lucense is designed around user ownership and privacy.

Contributions must:

- Avoid unnecessary collection or transmission of personal data.
- Never expose authentication tokens, cookies, credentials, or private user data.
- Avoid introducing hidden network requests.
- Clearly disclose any new external service integrations.
- Keep optional integrations optional.
- Never commit secrets, private keys, or personal configuration files.

## Pull Requests

A good pull request should:

1. Explain what the change does.
2. Explain why the change is necessary.
3. Reference related issues when applicable.
4. Include screenshots or recordings for UI changes when useful.
5. Include tests for new functionality or bug fixes where practical.
6. Mention any platform-specific limitations.
7. Update documentation when necessary.

Keep pull requests focused. Large changes should be discussed before implementation.

## Commit Messages

Use short, descriptive commit messages.

Examples:

- `fix: resolve playback interruption on Android`
- `feat: add support for additional audio formats`
- `perf: reduce library scanning overhead`
- `docs: improve Linux installation instructions`
- `refactor: simplify shared recognition logic`

These prefixes are recommendations, not strict requirements.

## Reporting Bugs

Use the GitHub issue tracker.

Include:

- Lucense version.
- Operating system and version.
- Steps to reproduce.
- Expected behavior.
- Actual behavior.
- Relevant logs or screenshots, with private information removed.

Do not publish credentials, cookies, private files, or other sensitive information.

## Feature Requests

Feature requests are welcome.

Describe:

- The problem you are trying to solve.
- The proposed behavior.
- Why the feature would be useful.
- Any relevant platform-specific considerations.

## License

By submitting a contribution, you agree that your contribution will be distributed under the project's MIT License.

Thank you for helping make Lucense better.
