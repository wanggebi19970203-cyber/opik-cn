<div align="center"><b><a href="README.md">English</a> | <a href="readme_CN.md">简体中文</a> | <a href="readme_JP.md">日本語</a> | <a href="readme_PT_BR.md">Português (Brasil)</a> | <a href="readme_KO.md">한국어</a><br><a href="readme_ES.md">Español</a> | <a href="readme_FR.md">Français</a> | <a href="readme_DE.md">Deutsch</a> | <a href="readme_RU.md">Русский</a> | <a href="readme_AR.md">العربية</a> | <a href="readme_HI.md">हिन्दी</a> | <a href="readme_TR.md">Türkçe</a></b></div>


<h1 align="center" style="border-bottom: none">
    <div>
        <a href="https://www.comet.com/site/products/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=header_img&utm_campaign=opik"><picture>
            <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/comet-ml/opik/refs/heads/main/apps/opik-documentation/documentation/static/img/logo-dark-mode.svg">
            <source media="(prefers-color-scheme: light)" srcset="https://raw.githubusercontent.com/comet-ml/opik/refs/heads/main/apps/opik-documentation/documentation/static/img/opik-logo.svg">
            <img alt="Comet Opik logo" src="https://raw.githubusercontent.com/comet-ml/opik/refs/heads/main/apps/opik-documentation/documentation/static/img/opik-logo.svg" width="200" />
        </picture></a>
        <br>
        Opik
    </div>
</h1>
<h2 align="center" style="border-bottom: none">开源 AI 可观测性、评估与优化平台</h2>
<p align="center">
Opik 帮助您构建、测试和优化生成式 AI 应用，使其从原型到生产运行得更好。无论是 RAG 聊天机器人、代码助手还是复杂的 Agent 系统，Opik 都提供全面的追踪、评估以及自动化的 Prompt 和工具优化，让 AI 开发不再靠猜。
</p>

<div align="center">

[![Python SDK](https://img.shields.io/pypi/v/opik)](https://pypi.org/project/opik/)
[![License](https://img.shields.io/github/license/comet-ml/opik)](https://github.com/comet-ml/opik/blob/main/LICENSE)
[![Build](https://github.com/comet-ml/opik/actions/workflows/build_apps.yml/badge.svg)](https://github.com/comet-ml/opik/actions/workflows/build_apps.yml)

</div>

<p align="center">
    <a href="https://www.comet.com/site/products/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=website_button&utm_campaign=opik"><b>官网</b></a> •
    <a href="https://chat.comet.com"><b>Slack 社区</b></a> •
    <a href="https://x.com/Cometml"><b>Twitter</b></a> •
    <a href="https://www.comet.com/docs/opik/changelog"><b>更新日志</b></a> •
    <a href="https://www.comet.com/docs/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=docs_button&utm_campaign=opik"><b>文档</b></a>
</p>

<div align="center" style="margin-top: 1em; margin-bottom: 1em;">
<a href="#-什么是-opik">🚀 什么是 Opik？</a> • <a href="#%EF%B8%8F-opik-服务器安装">🛠️ 服务器安装</a> • <a href="#-opik-客户端-sdk">💻 客户端 SDK</a> • <a href="#-通过集成记录追踪">📝 记录追踪</a><br>
<a href="#-llm-as-a-judge-指标">🧑‍⚖️ LLM 评估指标</a> • <a href="#-评估您的-llm-应用">🔍 评估应用</a> • <a href="#-在-github-上给我们-star">⭐ 给个 Star</a> • <a href="#-参与贡献">🤝 参与贡献</a>
</div>

<br>

[![Opik 平台截图](readme-thumbnail-new.png)](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=github&utm_content=readme_banner&utm_campaign=opik)

<a id="-什么是-opik"></a>
## 🚀 什么是 Opik？

Opik（由 [Comet](https://www.comet.com?from=llm&utm_source=opik&utm_medium=github&utm_content=what_is_opik_link&utm_campaign=opik) 构建）是一个开源平台，旨在简化 LLM 应用的整个生命周期。它赋能开发者评估、测试、监控和优化模型及 Agent 系统。核心能力包括：

- **全面可观测性**：深度追踪 LLM 调用、对话记录和 Agent 活动
- **高级评估**：强大的 Prompt 评估、LLM-as-a-Judge 和实验管理
- **生产就绪**：可扩展的监控仪表板和生产环境在线评估规则
- **Opik Agent Optimizer**：专用 SDK 和优化器集合，用于增强 Prompt 和 Agent
- **Opik Guardrails**：帮助您实施安全和负责任的 AI 实践

<br>

核心功能包括：

- **开发与追踪：**
  - 在开发和生产环境中追踪所有 LLM 调用和 Trace，包含详细上下文（[快速入门](https://www.comet.com/docs/opik/quickstart/?from=llm&utm_source=opik&utm_medium=github&utm_content=quickstart_link&utm_campaign=opik)）
  - 丰富的第三方集成，轻松实现可观测性：无缝对接众多框架，原生支持最流行的框架（包括 **Google ADK**、**Autogen**、**Flowise AI** 等）（[集成列表](https://www.comet.com/docs/opik/integrations/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=integrations_link&utm_campaign=opik)）
  - 通过 [Python SDK](https://www.comet.com/docs/opik/v1/tracing/annotate_traces/#annotating-traces-and-spans-using-the-sdk?from=llm&utm_source=opik&utm_medium=github&utm_content=sdk_link&utm_campaign=opik) 或 [UI](https://www.comet.com/docs/opik/tracing/annotate_traces/#annotating-traces-through-the-ui?from=llm&utm_source=opik&utm_medium=github&utm_content=ui_link&utm_campaign=opik) 为 Trace 和 Span 添加反馈评分
  - 在 [Prompt Playground](https://www.comet.com/docs/opik/prompt_engineering/playground) 中实验 Prompt 和模型

- **评估与测试**：
  - 使用[数据集](https://www.comet.com/docs/opik/v1/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_link&utm_campaign=opik)和[实验](https://www.comet.com/docs/opik/v1/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=eval_link&utm_campaign=opik)自动化 LLM 应用评估
  - 利用强大的 LLM-as-a-Judge 指标处理复杂任务，如[幻觉检测](https://www.comet.com/docs/opik/evaluation/metrics/hallucination/?from=llm&utm_source=opik&utm_medium=github&utm_content=hallucination_link&utm_campaign=opik)、[内容审核](https://www.comet.com/docs/opik/evaluation/metrics/moderation/?from=llm&utm_source=opik&utm_medium=github&utm_content=moderation_link&utm_campaign=opik)和 RAG 评估（[答案相关性](https://www.comet.com/docs/opik/evaluation/metrics/answer_relevance/?from=llm&utm_source=opik&utm_medium=github&utm_content=alex_link&utm_campaign=opik)、[上下文精确度](https://www.comet.com/docs/opik/evaluation/metrics/context_precision/?from=llm&utm_source=opik&utm_medium=github&utm_content=context_link&utm_campaign=opik)）
  - 通过 [PyTest 集成](https://www.comet.com/docs/opik/v1/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_link&utm_campaign=opik)将评估集成到 CI/CD 流水线

- **生产监控与优化**：
  - 记录大量生产 Trace：Opik 专为大规模设计（每日 4000 万+ 条 Trace）
  - 在 [Opik 仪表板](https://www.comet.com/docs/opik/v1/production/production_monitoring/?from=llm&utm_source=opik&utm_medium=github&utm_content=dashboard_link&utm_campaign=opik)中监控反馈评分、Trace 数量和 Token 使用量
  - 使用[在线评估规则](https://www.comet.com/docs/opik/v1/production/rules/?from=llm&utm_source=opik&utm_medium=github&utm_content=dashboard_link&utm_campaign=opik)配合 LLM-as-a-Judge 指标识别生产问题
  - 利用 **Opik Agent Optimizer** 和 **Opik Guardrails** 持续改进和保护生产环境中的 LLM 应用

> [!TIP]
> 如果您需要 Opik 目前没有的功能，请提交[功能请求](https://github.com/comet-ml/opik/issues/new/choose) 🚀

<br>

<a id="%EF%B8%8F-opik-服务器安装"></a>
## 🛠️ Opik 服务器安装

几分钟内启动 Opik 服务器。选择最适合您的方式：

### 方式一：Comet.com 云服务（最简单，推荐）

无需任何配置即可使用 Opik。适合快速启动和免维护使用。

👉 [创建免费 Comet 账户](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=github&utm_content=install_create_link&utm_campaign=opik)

### 方式二：自托管 Opik，完全掌控

在您自己的环境中部署 Opik。可选择 Docker 进行本地部署，或 Kubernetes 实现可扩展部署。

#### 使用 Docker Compose 自托管（本地开发与测试）

这是启动本地 Opik 实例最简单的方式。使用 `./opik.sh` 安装脚本：

Linux 或 Mac 环境：

```bash
# 克隆 Opik 仓库
git clone https://github.com/comet-ml/opik.git

# 进入仓库目录
cd opik

# 启动 Opik 平台
./opik.sh
```

Windows 环境：

```powershell
# 克隆 Opik 仓库
git clone https://github.com/comet-ml/opik.git

# 进入仓库目录
cd opik

# 启动 Opik 平台
powershell -ExecutionPolicy ByPass -c ".\\opik.ps1"
```

**开发服务配置**

Opik 安装脚本支持不同开发场景的服务配置：

```bash
# 启动完整 Opik 套件（默认行为）
./opik.sh

# 仅启动基础设施服务（数据库、缓存等）
./opik.sh --infra

# 启动基础设施 + 后端服务
./opik.sh --backend

# 在任何配置下启用 Guardrails
./opik.sh --guardrails          # 完整套件 + Guardrails
./opik.sh --backend --guardrails  # 基础设施 + 后端 + Guardrails
```

使用 `--help` 或 `--info` 选项排查问题。Dockerfile 确保容器以非 root 用户运行以增强安全性。启动完成后，访问 [localhost:5173](http://localhost:5173)。详细说明请参阅[本地部署指南](https://www.comet.com/docs/opik/self-host/local_deployment?from=llm&utm_source=opik&utm_medium=github&utm_content=self_host_link&utm_campaign=opik)。

#### 使用 Kubernetes 和 Helm 自托管（可扩展部署）

对于生产环境或大规模自托管部署，可使用 Helm chart 在 Kubernetes 集群上安装 Opik。点击徽章查看完整的 [Kubernetes 安装指南](https://www.comet.com/docs/opik/self-host/kubernetes/#kubernetes-installation?from=llm&utm_source=opik&utm_medium=github&utm_content=kubernetes_link&utm_campaign=opik)。

[![Kubernetes](https://img.shields.io/badge/Kubernetes-%23326ce5.svg?&logo=kubernetes&logoColor=white)](https://www.comet.com/docs/opik/self-host/kubernetes/#kubernetes-installation?from=llm&utm_source=opik&utm_medium=github&utm_content=kubernetes_link&utm_campaign=opik)

> [!IMPORTANT]
> **1.7.0 版本变更**：请查看[更新日志](https://github.com/comet-ml/opik/blob/main/CHANGELOG.md)了解重要更新和破坏性变更。

<a id="-opik-客户端-sdk"></a>
## 💻 Opik 客户端 SDK

Opik 提供一系列客户端库和 REST API 与 Opik 服务器交互，包括 Python、TypeScript 和 Ruby（通过 OpenTelemetry）的 SDK，可无缝集成到您的工作流中。详细的 API 和 SDK 参考请查看 [Opik 客户端参考文档](https://www.comet.com/docs/opik/reference/overview?from=llm&utm_source=opik&utm_medium=github&utm_content=reference_link&utm_campaign=opik)。

### Python SDK 快速开始

安装包：

```bash
# 使用 pip 安装
pip install opik

# 或使用 uv 安装
uv pip install opik
```

运行 `opik configure` 命令配置 Python SDK，它会提示您输入 Opik 服务器地址（自托管实例）或 API Key 和工作区（Comet.com）：

```bash
opik configure
```

> [!TIP]
> 您也可以在 Python 代码中调用 `opik.configure(use_local=True)` 来配置 SDK 连接本地自托管实例，或直接提供 Comet.com 的 API Key 和工作区详情。更多配置选项请参阅 [Python SDK 文档](https://www.comet.com/docs/opik/python-sdk-reference/?from=llm&utm_source=opik&utm_medium=github&utm_content=python_sdk_docs_link&utm_campaign=opik)。

现在您可以开始使用 [Python SDK](https://www.comet.com/docs/opik/python-sdk-reference/?from=llm&utm_source=opik&utm_medium=github&utm_content=sdk_link2&utm_campaign=opik) 记录追踪了。

<a id="-通过集成记录追踪"></a>
### 📝 通过集成记录追踪

记录追踪最简单的方式是使用我们的直接集成。Opik 支持众多框架，包括 **Google ADK**、**Autogen**、**AG2** 和 **Flowise AI** 等：

| 集成 | 描述 | 文档 |
| ---- | ---- | ---- |
| ADK | 记录 Google Agent Development Kit (ADK) 的追踪 | [文档](https://www.comet.com/docs/opik/integrations/adk?utm_source=opik&utm_medium=github&utm_content=google_adk_link&utm_campaign=opik) |
| AG2 | 记录 AG2 LLM 调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/ag2?utm_source=opik&utm_medium=github&utm_content=ag2_link&utm_campaign=opik) |
| Agent Spec | 记录 Agent Spec 调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/agentspec?utm_source=opik&utm_medium=github&utm_content=agentspec_link&utm_campaign=opik) |
| AIsuite | 记录 aisuite LLM 调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/aisuite?utm_source=opik&utm_medium=github&utm_content=aisuite_link&utm_campaign=opik) |
| Agno | 记录 Agno Agent 编排框架调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/agno?utm_source=opik&utm_medium=github&utm_content=agno_link&utm_campaign=opik) |
| Anthropic | 记录 Anthropic LLM 调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/anthropic?utm_source=opik&utm_medium=github&utm_content=anthropic_link&utm_campaign=opik) |
| Autogen | 记录 Autogen Agent 工作流的追踪 | [文档](https://www.comet.com/docs/opik/integrations/autogen?utm_source=opik&utm_medium=github&utm_content=autogen_link&utm_campaign=opik) |
| Bedrock | 记录 Amazon Bedrock LLM 调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/bedrock?utm_source=opik&utm_medium=github&utm_content=bedrock_link&utm_campaign=opik) |
| BeeAI (Python) | 记录 BeeAI Python Agent 框架调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/beeai?utm_source=opik&utm_medium=github&utm_content=beeai_link&utm_campaign=opik) |
| BeeAI (TypeScript) | 记录 BeeAI TypeScript Agent 框架调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/beeai-typescript?utm_source=opik&utm_medium=github&utm_content=beeai_typescript_link&utm_campaign=opik) |
| BytePlus | 记录 BytePlus LLM 调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/byteplus?utm_source=opik&utm_medium=github&utm_content=byteplus_link&utm_campaign=opik) |
| Cloudflare Workers AI | 记录 Cloudflare Workers AI 调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/cloudflare-workers-ai?utm_source=opik&utm_medium=github&utm_content=cloudflare_workers_ai_link&utm_campaign=opik) |
| Cohere | 记录 Cohere LLM 调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/cohere?utm_source=opik&utm_medium=github&utm_content=cohere_link&utm_campaign=opik) |
| CrewAI | 记录 CrewAI 调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/crewai?utm_source=opik&utm_medium=github&utm_content=crewai_link&utm_campaign=opik) |
| Cursor | 记录 Cursor 对话的追踪 | [文档](https://www.comet.com/docs/opik/integrations/cursor?utm_source=opik&utm_medium=github&utm_content=cursor_link&utm_campaign=opik) |
| DeepSeek | 记录 DeepSeek LLM 调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/deepseek?utm_source=opik&utm_medium=github&utm_content=deepseek_link&utm_campaign=opik) |
| Dify | 记录 Dify Agent 运行的追踪 | [文档](https://www.comet.com/docs/opik/integrations/dify?utm_source=opik&utm_medium=github&utm_content=dify_link&utm_campaign=opik) |
| DSPY | 记录 DSPy 运行的追踪 | [文档](https://www.comet.com/docs/opik/integrations/dspy?utm_source=opik&utm_medium=github&utm_content=dspy_link&utm_campaign=opik) |
| Fireworks AI | 记录 Fireworks AI LLM 调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/fireworks-ai?utm_source=opik&utm_medium=github&utm_content=fireworks_ai_link&utm_campaign=opik) |
| Flowise AI | 记录 Flowise AI 可视化 LLM 构建器的追踪 | [文档](https://www.comet.com/docs/opik/integrations/flowise?utm_source=opik&utm_medium=github&utm_content=flowise_link&utm_campaign=opik) |
| Gemini (Python) | 记录 Google Gemini LLM 调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/gemini?utm_source=opik&utm_medium=github&utm_content=gemini_link&utm_campaign=opik) |
| Gemini (TypeScript) | 记录 Google Gemini TypeScript SDK 调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/gemini-typescript?utm_source=opik&utm_medium=github&utm_content=gemini_typescript_link&utm_campaign=opik) |
| Groq | 记录 Groq LLM 调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/groq?utm_source=opik&utm_medium=github&utm_content=groq_link&utm_campaign=opik) |
| Guardrails | 记录 Guardrails AI 验证的追踪 | [文档](https://www.comet.com/docs/opik/integrations/guardrails-ai?utm_source=opik&utm_medium=github&utm_content=guardrails_link&utm_campaign=opik) |
| Haystack | 记录 Haystack 调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/haystack?utm_source=opik&utm_medium=github&utm_content=haystack_link&utm_campaign=opik) |
| Harbor | 记录 Harbor 基准评估试验的追踪 | [文档](https://www.comet.com/docs/opik/integrations/harbor?utm_source=opik&utm_medium=github&utm_content=harbor_link&utm_campaign=opik) |
| Instructor | 记录使用 Instructor 的 LLM 调用追踪 | [文档](https://www.comet.com/docs/opik/integrations/instructor?utm_source=opik&utm_medium=github&utm_content=instructor_link&utm_campaign=opik) |
| LangChain (Python) | 记录 LangChain LLM 调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/langchain?utm_source=opik&utm_medium=github&utm_content=langchain_link&utm_campaign=opik) |
| LangChain (JS/TS) | 记录 LangChain JavaScript/TypeScript 调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/langchainjs?utm_source=opik&utm_medium=github&utm_content=langchainjs_link&utm_campaign=opik) |
| LangGraph | 记录 LangGraph 执行的追踪 | [文档](https://www.comet.com/docs/opik/integrations/langgraph?utm_source=opik&utm_medium=github&utm_content=langgraph_link&utm_campaign=opik) |
| Langflow | 记录 Langflow 可视化 AI 构建器的追踪 | [文档](https://www.comet.com/docs/opik/integrations/langflow?utm_source=opik&utm_medium=github&utm_content=langflow_link&utm_campaign=opik) |
| LiteLLM | 记录 LiteLLM 模型调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/litellm?utm_source=opik&utm_medium=github&utm_content=litellm_link&utm_campaign=opik) |
| LiveKit Agents | 记录 LiveKit Agents AI Agent 框架调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/livekit?utm_source=opik&utm_medium=github&utm_content=livekit_link&utm_campaign=opik) |
| LlamaIndex | 记录 LlamaIndex LLM 调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/llama_index?utm_source=opik&utm_medium=github&utm_content=llama_index_link&utm_campaign=opik) |
| Mastra | 记录 Mastra AI 工作流框架调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/mastra?utm_source=opik&utm_medium=github&utm_content=mastra_link&utm_campaign=opik) |
| Microsoft Agent Framework (Python) | 记录 Microsoft Agent Framework 调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/microsoft-agent-framework?utm_source=opik&utm_medium=github&utm_content=agent_framework_link&utm_campaign=opik) |
| Microsoft Agent Framework (.NET) | 记录 Microsoft Agent Framework .NET 调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/microsoft-agent-framework-dotnet?utm_source=opik&utm_medium=github&utm_content=agent_framework_dotnet_link&utm_campaign=opik) |
| Mistral AI | 记录 Mistral AI LLM 调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/mistral?utm_source=opik&utm_medium=github&utm_content=mistral_link&utm_campaign=opik) |
| n8n | 记录 n8n 工作流执行的追踪 | [文档](https://www.comet.com/docs/opik/integrations/n8n?utm_source=opik&utm_medium=github&utm_content=n8n_link&utm_campaign=opik) |
| Novita AI | 记录 Novita AI LLM 调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/novita-ai?utm_source=opik&utm_medium=github&utm_content=novita_ai_link&utm_campaign=opik) |
| Ollama | 记录 Ollama LLM 调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/ollama?utm_source=opik&utm_medium=github&utm_content=ollama_link&utm_campaign=opik) |
| OpenAI (Python) | 记录 OpenAI LLM 调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/openai?utm_source=opik&utm_medium=github&utm_content=openai_link&utm_campaign=opik) |
| OpenAI (JS/TS) | 记录 OpenAI JavaScript/TypeScript 调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/openai-typescript?utm_source=opik&utm_medium=github&utm_content=openai_typescript_link&utm_campaign=opik) |
| OpenAI Agents | 记录 OpenAI Agents SDK 调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/openai_agents?utm_source=opik&utm_medium=github&utm_content=openai_agents_link&utm_campaign=opik) |
| OpenClaw | 记录 OpenClaw Agent 运行的追踪 | [文档](https://www.comet.com/docs/opik/integrations/openclaw?utm_source=opik&utm_medium=github&utm_content=openclaw_link&utm_campaign=opik) |
| OpenRouter | 记录 OpenRouter LLM 调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/openrouter?utm_source=opik&utm_medium=github&utm_content=openrouter_link&utm_campaign=opik) |
| OpenTelemetry | 记录 OpenTelemetry 支持的调用追踪 | [文档](https://www.comet.com/docs/opik/tracing/opentelemetry/overview?utm_source=opik&utm_medium=github&utm_content=opentelemetry_link&utm_campaign=opik) |
| OpenWebUI | 记录 OpenWebUI 对话的追踪 | [文档](https://www.comet.com/docs/opik/integrations/openwebui?utm_source=opik&utm_medium=github&utm_content=openwebui_link&utm_campaign=opik) |
| Pipecat | 记录 Pipecat 实时语音 Agent 调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/pipecat?utm_source=opik&utm_medium=github&utm_content=pipecat_link&utm_campaign=opik) |
| Predibase | 记录 Predibase LLM 调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/predibase?utm_source=opik&utm_medium=github&utm_content=predibase_link&utm_campaign=opik) |
| Pydantic AI | 记录 PydanticAI Agent 调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/pydantic-ai?utm_source=opik&utm_medium=github&utm_content=pydantic_ai_link&utm_campaign=opik) |
| Ragas | 记录 Ragas 评估的追踪 | [文档](https://www.comet.com/docs/opik/integrations/ragas?utm_source=opik&utm_medium=github&utm_content=ragas_link&utm_campaign=opik) |
| Semantic Kernel | 记录 Microsoft Semantic Kernel 调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/semantic-kernel?utm_source=opik&utm_medium=github&utm_content=semantic_kernel_link&utm_campaign=opik) |
| Smolagents | 记录 Smolagents Agent 的追踪 | [文档](https://www.comet.com/docs/opik/integrations/smolagents?utm_source=opik&utm_medium=github&utm_content=smolagents_link&utm_campaign=opik) |
| Spring AI | 记录 Spring AI 框架调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/spring-ai?utm_source=opik&utm_medium=github&utm_content=spring_ai_link&utm_campaign=opik) |
| Strands Agents | 记录 Strands Agents 调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/strands-agents?utm_source=opik&utm_medium=github&utm_content=strands_agents_link&utm_campaign=opik) |
| Together AI | 记录 Together AI LLM 调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/together-ai?utm_source=opik&utm_medium=github&utm_content=together_ai_link&utm_campaign=opik) |
| Vercel AI SDK | 记录 Vercel AI SDK 调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/vercel-ai-sdk?utm_source=opik&utm_medium=github&utm_content=vercel_ai_sdk_link&utm_campaign=opik) |
| VoltAgent | 记录 VoltAgent Agent 框架调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/voltagent?utm_source=opik&utm_medium=github&utm_content=voltagent_link&utm_campaign=opik) |
| WatsonX | 记录 IBM watsonx LLM 调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/watsonx?utm_source=opik&utm_medium=github&utm_content=watsonx_link&utm_campaign=opik) |
| xAI Grok | 记录 xAI Grok LLM 调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/xai-grok?utm_source=opik&utm_medium=github&utm_content=xai_grok_link&utm_campaign=opik) |

> [!TIP]
> 如果您使用的框架未在上方列出，请[提交 Issue](https://github.com/comet-ml/opik/issues) 或提交包含集成的 PR。

如果您未使用上述任何框架，也可以使用 `track` 装饰器来[记录追踪](https://www.comet.com/docs/opik/v1/tracing/log_traces/?from=llm&utm_source=opik&utm_medium=github&utm_content=traces_link&utm_campaign=opik)：

```python
import opik

opik.configure(use_local=True) # 本地运行

@opik.track
def my_llm_function(user_question: str) -> str:
    # 您的 LLM 代码
    return "Hello"
```

> [!TIP]
> track 装饰器可与任何集成配合使用，也可用于追踪嵌套函数调用。

<a id="-llm-as-a-judge-指标"></a>
### 🧑‍⚖️ LLM-as-a-Judge 指标

Python Opik SDK 包含多种 LLM-as-a-Judge 指标，帮助您评估 LLM 应用。更多信息请参阅[指标文档](https://www.comet.com/docs/opik/evaluation/metrics/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=metrics_2_link&utm_campaign=opik)。

使用方法：

```python
from opik.evaluation.metrics import Hallucination

metric = Hallucination()
score = metric.score(
    input="What is the capital of France?",
    output="Paris",
    context=["France is a country in Europe."]
)
print(score)
```

Opik 还包含多种预构建的启发式指标，以及创建自定义指标的能力。更多信息请参阅[指标文档](https://www.comet.com/docs/opik/evaluation/metrics/overview?from=llm&utm_source=opik&utm_medium=github&utm_content=metrics_3_link&utm_campaign=opik)。

<a id="-评估您的-llm-应用"></a>
### 🔍 评估您的 LLM 应用

Opik 允许您在开发阶段通过[数据集](https://www.comet.com/docs/opik/v1/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_2_link&utm_campaign=opik)和[实验](https://www.comet.com/docs/opik/v1/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=experiments_link&utm_campaign=opik)评估 LLM 应用。Opik 仪表板提供增强的实验图表和更好的大 Trace 处理。您还可以使用 [PyTest 集成](https://www.comet.com/docs/opik/v1/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_2_link&utm_campaign=opik)在 CI/CD 流水线中运行评估。

<a id="-在-github-上给我们-star"></a>
## ⭐ 在 GitHub 上给我们 Star

如果您觉得 Opik 有帮助，请考虑给我们一个 Star！您的支持帮助我们扩大社区并持续改进产品。

[![Star History Chart](https://api.star-history.com/svg?repos=comet-ml/opik&type=Date)](https://github.com/comet-ml/opik)

<a id="-参与贡献"></a>
## 🤝 参与贡献

参与 Opik 贡献的方式有很多：

- 提交 [Bug 报告](https://github.com/comet-ml/opik/issues)和[功能请求](https://github.com/comet-ml/opik/issues)
- 审查文档并提交 [Pull Request](https://github.com/comet-ml/opik/pulls) 改进文档
- 在演讲或文章中介绍 Opik 并[告诉我们](https://chat.comet.com)
- 为[热门功能请求](https://github.com/comet-ml/opik/issues?q=is%3Aissue+is%3Aopen+label%3A%22enhancement%22)投票表示支持

了解更多关于如何参与 Opik 贡献的信息，请查看我们的[贡献指南](CONTRIBUTING.md)。
