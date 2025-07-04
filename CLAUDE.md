# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview
Feather Wand - JMeter Agent is a sophisticated JMeter plugin that integrates AI capabilities (Claude, OpenAI, and AWS Bedrock) to assist performance engineers in creating, optimizing, and managing JMeter test plans. The plugin provides a chat interface within JMeter for AI-assisted test plan development.

## Development Commands

### Building the Project
```bash
mvn clean package
```
This creates a JAR file in the `target/` directory that can be installed in JMeter's `lib/ext/` directory.

### Running Tests
```bash
mvn test
```
Tests are written with JUnit 5 and Mockito. Key test classes:
- `CodeRefactorerTest` - Tests AI-powered code refactoring
- `BedrockServiceTest` - Tests AWS Bedrock service integration
- `CommandIntellisenseProviderTest` - Tests command completion
- `InputBoxIntellisenseTest` - Tests input box intellisense
- `VersionUtilsTest` - Tests version utilities

### Development Environment
- **Java**: 8/9 (source 9, target 9 in compiler plugin)
- **Maven**: 3.x for build management
- **JMeter**: 5.6.3 for plugin integration
- **IDE**: Any Java IDE with Maven support

## Code Architecture

### Core Entry Points
- **`AiMenuCreator`**: Implements JMeter's `MenuCreator` interface to add AI functionality to JMeter's Run menu
- **`AiChatPanel`**: Main UI component (1462 lines) - the heart of the plugin providing the chat interface
- **`AI`**: Abstract action handler with keyboard shortcut (Alt+V)

### Service Layer
- **`AiService`**: Interface for AI service implementations
- **`ClaudeService`**: Anthropic Claude API integration (409 lines)
- **`OpenAiService`**: OpenAI API integration
- **`BedrockService`**: AWS Bedrock Claude API integration (350+ lines)
- **`CodeRefactorer`**: AI-powered code refactoring for JSR223 scripts

### Configuration Management
- **`AiConfig`**: Configuration wrapper around JMeter properties
- Configuration via `jmeter-ai-sample.properties` copied to JMeter's properties files
- Supports Claude, OpenAI, and AWS Bedrock configurations

### Command System
Each special command has its own handler:
- **`LintCommandHandler`**: Handles `@lint` command for element renaming
- **`WrapCommandHandler`**: Handles `@wrap` command for grouping HTTP requests
- **`OptimizeRequestHandler`**: Handles `@optimize` command
- **`UsageCommandHandler`**: Handles `@usage` command for AI usage statistics
- **`CodeCommandHandler`**: Handles code-related operations

### JMeter Integration
- **`JMeterElementManager`**: Core utility for programmatic test plan manipulation (1040 lines)
- **`JMeterElementRequestHandler`**: Processes natural language element creation requests
- **`ElementSuggestionManager`**: Dynamic button creation for suggested JMeter elements
- **`JSR223ContextMenu`**: Right-click context menu for JSR223 script editors (313 lines)

### UI Components
- **`MessageProcessor`**: Handles markdown rendering, code block display (308 lines)
- **`TreeNavigationButtons`**: Up/down navigation in test plan tree
- **`ConversationManager`**: Conversation history management
- **`ChatUIManager`**: UI state management

### Intellisense System
- **`InputBoxIntellisense`**: Provides command completion (145 lines)
- **`CommandIntellisenseProvider`**: Command suggestion engine
- **`IntellisensePopup`**: UI popup for suggestions

## Key Design Patterns
- **Strategy Pattern**: `AiService` interface with multiple implementations
- **Command Pattern**: Command handlers for different operations
- **Observer Pattern**: Property change listeners for UI updates
- **Factory Pattern**: Element creation in `JMeterElementManager`
- **Background Processing**: All AI operations run in `SwingWorker` threads

## JMeter Element Support
The plugin supports 100+ JMeter elements across all categories:
- **Samplers**: HTTP, JDBC, FTP, Java, LDAP, TCP, SMTP, etc.
- **Controllers**: Loop, If, While, Transaction, Simple, etc.
- **Config Elements**: CSV Data Set, Header Manager, Cookie Manager, etc.
- **Assertions**: Response, JSON Path, XPath, Duration, Size, etc.
- **Timers**: Constant, Random (Uniform, Gaussian, Poisson), etc.
- **Extractors**: Regex, XPath, JSON Path, Boundary, etc.
- **Listeners**: View Results Tree, Aggregate Report, Backend Listener, etc.
- **JSR223 Elements**: All JSR223 variants (Sampler, PreProcessor, PostProcessor, etc.)

## Special Commands
- **`@this`**: Get information about currently selected element
- **`@optimize`**: Get optimization recommendations for selected element
- **`@lint`**: Automatically rename elements for better organization
- **`@wrap`**: Group HTTP samplers under Transaction Controllers
- **`@usage`**: View AI API usage statistics
- **`@code`**: Extract code blocks from AI responses into JSR223 editor

## Configuration Files
- **`jmeter-ai-sample.properties`**: Complete configuration template with extensive documentation
- **`pom.xml`**: Maven configuration with dependency management and build plugins
- **`README.md`**: Comprehensive user documentation with feature explanations

## Dependencies
- **Anthropic Java SDK 0.3.0**: Claude API integration
- **OpenAI Java SDK 0.31.0**: OpenAI API integration
- **AWS SDK for Java 2.20.162**: AWS Bedrock integration
- **Jackson Databind 2.15.2**: JSON processing for Bedrock
- **JMeter 5.6.3**: Core JMeter functionality
- **JUnit 5 + Mockito**: Testing framework
- **SLF4J + Log4j**: Logging framework

## Development Notes
- All AI operations run in background threads to prevent UI blocking
- Comprehensive error handling with user-friendly messages
- Undo/Redo support for AI operations (Cmd/Ctrl+Z)
- Extensive logging configuration for debugging AI service calls
- Natural language processing converts user requests to JMeter elements
- Context-aware suggestions based on current test plan selection
- Conversation history management for context-aware AI responses

## Build Plugins
- **Maven Assembly Plugin**: Creates JAR with dependencies
- **Maven Shade Plugin**: Handles dependency conflicts with JMeter
- **Maven Surefire Plugin**: Configured for JUnit 5 test execution