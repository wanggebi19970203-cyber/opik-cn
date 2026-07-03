export const LANGUAGES = {
  en: 'English',
  zh: '中文',
} as const;

export type Language = keyof typeof LANGUAGES;

export const DEFAULT_LANGUAGE: Language = 'zh';
export const FALLBACK_LANGUAGE: Language = 'en';
