// 此文件由 Fern 根据我们的 API 定义自动生成。

export interface ServiceTogglesConfig {
    pythonEvaluatorEnabled: boolean;
    traceThreadPythonEvaluatorEnabled: boolean;
    spanLlmAsJudgeEnabled: boolean;
    spanUserDefinedMetricPythonEnabled: boolean;
    guardrailsEnabled: boolean;
    opikAiEnabled: boolean;
    alertsEnabled: boolean;
    welcomeWizardEnabled: boolean;
    exportEnabled: boolean;
    costIntelligenceEnabled: boolean;
    datasetVersioningEnabled: boolean;
    datasetExportEnabled: boolean;
    demoDataEnabled: boolean;
    openaiProviderEnabled: boolean;
    anthropicProviderEnabled: boolean;
    geminiProviderEnabled: boolean;
    openrouterProviderEnabled: boolean;
    vertexaiProviderEnabled: boolean;
    bedrockProviderEnabled: boolean;
    customllmProviderEnabled: boolean;
    ollamaProviderEnabled: boolean;
    ollieEnabled: boolean;
    projectHomepageEnabled: boolean;
    agenticToolsEnabled: boolean;
    onlineScoringTracingEnabled: boolean;
    defaultPageSize?: number;
}
