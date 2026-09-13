# Contributing to CultAC

Thank you for your interest in contributing to CultAC. This document outlines the guidelines for
making pull
requests to the project. *We're usually pretty lenient with pull requests, but this guide will help
make the process go more smoothly.*

### Pull Request Guidelines

- **Compatibility**
  - The plugin must not entirely break with legacy clients (1.8-1.21.1), you may exempt older clients
  - The plugin must be able to run on Java 17 or higher. Changes that don't support Java 17 at runtime will not be accepted.

- **Non-acceptable pull requests**
  - Heuristic-based checks will be accepted ONLY IF they are mathematically reasonable and unlikely to cause false positives
  - Changes that require large dependencies must be justified, i.e. machine learning libraries

- **Pull request formatting**
  - Create a new branch for your feature or fix when forking the repository.
  - Reference related issues in your pull request description if applicable.
  - Write clear and descriptive commit messages.
  - Don't use AI to write a description of your pull request, you must understand the code yourself.

- **Code styling**
  - Add code comments for complex logic or significant changes.
  - Try to keep your code clean and avoid duplication.
  - Thoroughly test your changes before submitting your pull request.

### Development Notes

- CultAC is built using [Gradle](https://gradle.org/) kotlin scripts.
- JDK 25 is required to build the project; the plugin classes target Java 21.
- Bedrock geometry is checked into this repo as it can only be regenerated on Linux
