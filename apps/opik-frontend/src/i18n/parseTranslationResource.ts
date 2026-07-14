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

const toCamelCase = (value: string): string =>
  value.replace(/-([a-z])/g, (_, character: string) => character.toUpperCase());

const toKebabCase = (value: string): string =>
  value.replace(/([a-z0-9])([A-Z])/g, "$1-$2").toLowerCase();

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
  const camelCaseNamespaceKey = namespaceKey
    ? toCamelCase(namespaceKey)
    : undefined;
  const matchedNamespaceKey = namespaceKey
    ? (isTranslationResource(resource[namespaceKey]) && namespaceKey) ||
      (camelCaseNamespaceKey &&
        isTranslationResource(resource[camelCaseNamespaceKey]) &&
        camelCaseNamespaceKey)
    : undefined;
  const namespacedResource = namespaceKey
    ? resource[namespaceKey] ??
      (camelCaseNamespaceKey ? resource[camelCaseNamespaceKey] : undefined)
    : undefined;

  if (!isTranslationResource(namespacedResource)) {
    return resource;
  }

  const parsedResource = {
    ...resource,
    ...namespacedResource,
  };

  if (!namespace || ROOT_NAMESPACES.has(namespace) || !matchedNamespaceKey) {
    return parsedResource;
  }

  return {
    ...parsedResource,
    [matchedNamespaceKey]: parsedResource,
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

  const fileNamespace = ROOT_NAMESPACES.has(namespace)
    ? namespace
    : namespace.startsWith("pages/")
      ? `pages/${toKebabCase(namespace.slice("pages/".length))}`
      : `pages/${toKebabCase(namespace)}`;

  return `/locales/${language}/${fileNamespace}.json?v=${encodeURIComponent(
    TRANSLATION_ASSET_VERSION,
  )}`;
};

export default parseTranslationResource;
