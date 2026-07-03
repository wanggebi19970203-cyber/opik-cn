/**
 * 当请求的提示词或版本在后端中未找到时抛出
 */
export class PromptNotFoundError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "PromptNotFoundError";
    Object.setPrototypeOf(this, PromptNotFoundError.prototype);
  }
}

/**
 * 当模板占位符验证失败或变量缺失时抛出
 */
export class PromptPlaceholderError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "PromptPlaceholderError";
    Object.setPrototypeOf(this, PromptPlaceholderError.prototype);
  }
}

/**
 * 通用提示词验证失败时抛出（无效模板语法等）
 */
export class PromptValidationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "PromptValidationError";
    Object.setPrototypeOf(this, PromptValidationError.prototype);
  }
}

/**
 * 当引用工作区中未注册的环境时抛出。
 */
export class EnvironmentNotFoundError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "EnvironmentNotFoundError";
    Object.setPrototypeOf(this, EnvironmentNotFoundError.prototype);
  }
}

/**
 * 当尝试访问与现有模板结构不同的提示词时抛出。
 * 模板结构（文本 vs 聊天）在创建后不可变。
 */
export class PromptTemplateStructureMismatch extends Error {
  public readonly promptName: string;
  public readonly existingStructure: string;
  public readonly attemptedStructure: string;

  constructor(
    promptName: string,
    existingStructure: string,
    attemptedStructure: string
  ) {
    const message = `Prompt '${promptName}' has template_structure='${existingStructure}' but attempted to access as '${attemptedStructure}'. Template structure is immutable after creation.`;
    super(message);
    this.name = "PromptTemplateStructureMismatch";
    this.promptName = promptName;
    this.existingStructure = existingStructure;
    this.attemptedStructure = attemptedStructure;
    Object.setPrototypeOf(this, PromptTemplateStructureMismatch.prototype);
  }
}
