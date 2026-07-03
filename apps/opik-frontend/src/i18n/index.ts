import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import LanguageDetector from 'i18next-browser-languagedetector';
import HttpBackend from 'i18next-http-backend';

i18n
  .use(HttpBackend)
  .use(LanguageDetector)
  .use(initReactI18next)
  .init({
    lng: 'zh', // 默认中文
    fallbackLng: 'en',
    debug: import.meta.env.DEV,
    
    interpolation: {
      escapeValue: false, // React已经做了XSS防护
    },
    
    backend: {
      loadPath: '/locales/{{lng}}/{{ns}}.json',
    },
    
    ns: ['common', 'navigation', 'actions', 'forms', 'messages', 'dialogs', 'datasets', 'tracing', 'onboarding', 'sme', 'test-suite-items', 'test-suites', 'trial', 'dashboards', 'compare-experiments', 'annotation-queue', 'optimizations', 'optimization', 'online-evaluation', 'prompt', 'home'],
    defaultNS: 'common',
    
    react: {
      useSuspense: false,
    },
  });

export default i18n;
