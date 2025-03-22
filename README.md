# 🚀 Feather Wand - JMeter Agent

This plugin provides a simple way to chat with AI in JMeter. Feather Wand serves as your intelligent assistant for JMeter test plan development, optimization, and troubleshooting.

> 🪄 **About the name**: The name "Feather Wand" was suggested by my children who were inspired by an episode of the animated show Bluey. In the episode, a simple feather becomes a magical wand that transforms the ordinary into something special (heavy) - much like how this plugin aims to transform your JMeter experience with a touch of AI magic!

![Feather Wand](./images/Feather-Wand-AI-Agent-JMeter.png)

## ✨ Features

- Chat with AI directly within JMeter
- Get suggestions for JMeter elements based on your needs
- Ask questions about JMeter functionality and best practices
- Use `@this` command to get detailed information about the currently selected element
- Use `@lint` command to automatically rename elements in your test plan for better organization and readability
- Use `@optimize` command to get optimization recommendations for the currently selected element in your test plan
- Use `@code` command to extract code blocks from AI responses and insert them directly into JSR223 components
- Use `@wrap` command to intelligently group HTTP samplers under Transaction Controllers for better organization and reporting
- Customize AI behavior through configuration properties

## ⚠️ Disclaimer and Best Practices

While the Feather Wand plugin aims to provide helpful assistance, please keep the following in mind:

- **AI Limitations**: The AI can make mistakes or provide incorrect information. Always verify critical suggestions before implementing them in production tests.
- **Backup Your Test Plans**: Always backup your test plans before making significant changes, especially when implementing AI suggestions.
- **Test Verification**: After making changes based on AI recommendations, thoroughly verify your test plan functionality in a controlled environment before running it against production systems.
- **Performance Impact**: Some AI-suggested configurations may impact test performance. Monitor resource usage when implementing new configurations.
- **Security Considerations**: Do not share sensitive information (credentials, proprietary code, etc.) in your conversations with the AI.
- **API Costs**: Be aware that using the Claude API incurs costs based on token usage. The plugin is designed to minimize token usage, but extensive use will affect your Anthropic account billing.

This plugin is provided as a tool to assist JMeter users, but the ultimate responsibility for test plan design, implementation, and execution remains with the user.

## ⚙️ Configuration

The Feather Wand plugin can be configured through JMeter properties. Copy the `jmeter-ai-sample.properties` file content to your `jmeter.properties` or `user.properties` file and modify the properties as needed.

### 🔧 Available Configuration Options

| Property                  | Description                                                  | Default Value              |
| ------------------------- | ------------------------------------------------------------ | -------------------------- |
| `anthropic.api.key`       | Your Claude API key                                          | Required                   |
| `claude.default.model`    | Default Claude model to use                                  | claude-3-sonnet-20240229   |
| `claude.temperature`      | Temperature setting (0.0-1.0)                                | 0.7                        |
| `claude.max.tokens`       | Maximum tokens for AI responses                              | 1024                       |
| `claude.max.history.size` | Maximum conversation history size                            | 10                         |
| `claude.system.prompt`    | System prompt that guides Claude's responses                 | See sample properties file |
| `anthropic.log.level`     | Logging level for Anthropic API requests ("info" or "debug") | Empty (disabled)           |

### 💬 Customizing the System Prompt

The system prompt defines how Claude responds to your queries. You can customize this in the properties file to focus on specific aspects of JMeter or add your own guidelines. The default prompt is designed to provide helpful, JMeter-specific responses.

## 🔍 Special Commands

### 🪄 @this Command

Use the `@this` command in your message to get detailed information about the currently selected element in your test plan. For example:

- "Tell me about @this element"
- "How can I optimize @this?"
- "What are the best practices for @this?"

Feather Wand will analyze the selected element and provide tailored information and advice.

### 🔧 @optimize Command

Use the `@optimize` command (or simply type "optimize") to get optimization recommendations for the currently selected element in your test plan. This command will:

1. Analyze the selected element's configuration
2. Identify potential performance bottlenecks
3. Suggest specific, actionable improvements
4. Provide best practices for that element type

For example, if you have an HTTP Request sampler selected, the optimization recommendations might include:

- Connection and timeout settings adjustments
- Proper header management
- Efficient parameter handling
- Encoding settings optimization
- Redirect handling improvements

Simply select an element in your test plan and type `@optimize` or `optimize` in the chat to receive tailored optimization recommendations.

### 💻 @code Command

Use the `@code` command to extract code blocks from AI responses and insert them directly into JSR223 components. This feature streamlines the process of implementing scripts suggested by the AI:

1. **How to Use**:
   - Select a JSR223 component in your test plan
   - Ask the AI for a script (e.g., "Write a JSR223 script to extract values from JSON response")
   - When the AI responds with code blocks, type `@code` to extract and insert the code

2. **Benefits**:
   - Eliminates manual copy-pasting of code
   - Preserves proper formatting and indentation
   - Automatically extracts only the code blocks, ignoring explanatory text
   - Maintains the original AI response in the chat for reference

This feature is particularly useful when implementing complex scripts or when you need to quickly apply the AI's code suggestions to your test plan.

### 🧹 @lint Command

Use the `@lint` command to automatically rename elements in your test plan for better organization and readability:

1. **How to Use**:
   - Type `@lint` in the chat to analyze your test plan structure
   - The AI will suggest better names for elements based on their function and context
   - Review the suggestions and confirm to apply the changes
   - Use the undo/redo buttons to revert or reapply changes if needed
   - e.g. `@lint rename the elements based on the URL` or `@lint rename the elements in pascal case`

2. **Benefits**:
   - Improves test plan readability and maintenance
   - Applies consistent naming conventions across your test plan
   - Helps identify elements with generic or unclear names
   - Makes test plans more understandable for team members
   - Undo it if you don't like the changes
   - Redo it if you like the changes

3. **Best Practices**:
   - Run `@lint` after creating a new test plan to establish good naming from the start
   - Use it before sharing test plans with team members
   - Apply it to imported test plans to make them conform to your naming standards

This feature is particularly valuable for large test plans or when working in teams where consistent naming is essential for collaboration.

### 📦 @wrap Command

Use the `@wrap` command to intelligently group HTTP samplers under Transaction Controllers for better organization and reporting:

1. **How to Use**:
   - Select a Thread Group in your test plan
   - Type `@wrap` in the chat
   - The AI will analyze your HTTP samplers and group similar ones under Transaction Controllers
   - Use the undo button to revert changes if needed

2. **Benefits**:
   - Improves test plan organization and readability
   - Enhances test reports with meaningful transaction metrics
   - Groups related HTTP requests logically
   - Preserves the original order and hierarchy of samplers
   - Maintains all child elements (like assertions and post-processors) with their parent samplers

3. **How It Works**:
   - Analyzes sampler names and paths to identify logical groupings
   - Creates appropriately named Transaction Controllers
   - Moves samplers under their respective Transaction Controllers
   - Preserves the original order and hierarchy
   - Uses pattern matching and structural analysis (not AI) for its grouping logic

This feature is especially useful for imported or recorded test plans that contain many individual HTTP samplers without proper organization.

## 🗝️ How to get an Anthropic API key?

1. Go to [Anthropic API](https://www.anthropic.com/) website
2. Sign up for an account
3. Create a new API key
4. Copy the API key and paste it into the `anthropic.api.key` property in your `jmeter.properties` file
5. For more information about the API key, visit the [API Key documentation](https://www.anthropic.com/api)

## 🪲Report Issues

If you encounter any issues or have suggestions for improvement, please open an issue on the [GitHub repository](https://github.com/qainsights/jmeter-ai).

## ⛳️ Roadmap

Please check the [roadmap](https://github.com/users/QAInsights/projects/12) for more details.