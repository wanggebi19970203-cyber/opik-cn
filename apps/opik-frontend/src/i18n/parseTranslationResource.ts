type TranslationResource = Record<string, unknown>;

const ROOT_NAMESPACES = new Set([
  "common",
  "navigation",
  "actions",
  "forms",
  "messages",
  "dialogs",
]);

const TRANSLATION_ASSET_VERSION =
  new URL(import.meta.url).pathname.split("/").at(-1) ?? "local";

const isTranslationResource = (value: unknown): value is TranslationResource =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const parseTranslationResource = (
  data: string,
  _languages?: string | string[],
  namespaces?: string | string[],
): TranslationResource => {
  const resource: unknown = JSON.parse(data);

  if (!isTranslationResource(resource)) {
    return {};
  }

  const namespace = Array.isArray(namespaces) ? namespaces[0] : namespaces;
  const namespaceKey = namespace?.split("/").at(-1);
  const namespacedResource = namespaceKey ? resource[namespaceKey] : undefined;

  if (!isTranslationResource(namespacedResource)) {
    return resource;
  }

  return {
    ...resource,
    ...namespacedResource,
  };
};

export const getTranslationLoadPath = (
  languages: string[],
  namespaces: string[],
): string => {
  const language = languages[0];
  const namespace = namespaces[0];

  if (!language || !namespace) {
    return "";
  }

  const fileNamespace =
    ROOT_NAMESPACES.has(namespace) || namespace.startsWith("pages/")
      ? namespace
      : `pages/${namespace}`;

  return `/locales/${language}/${fileNamespace}.json?v=${encodeURIComponent(
    TRANSLATION_ASSET_VERSION,
  )}`;
};

export default parseTranslationResource;
