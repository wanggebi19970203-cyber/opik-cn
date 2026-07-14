import i18next from "i18next";
import {
  GuardrailComputedResult,
  GuardrailResult,
  GuardrailTypes,
  GuardrailValidation,
  PiiSupportedEntities,
} from "@/types/guardrails";

export const PIIEntitiesLabelMap = {
  [PiiSupportedEntities.CREDIT_CARD]: i18next.t(
    "common.constants.guardrails.pii.creditCard",
  ),
  [PiiSupportedEntities.PHONE_NUMBER]: i18next.t(
    "common.constants.guardrails.pii.phoneNumber",
  ),
  [PiiSupportedEntities.EMAIL_ADDRESS]: i18next.t(
    "common.constants.guardrails.pii.emailAddress",
  ),
  [PiiSupportedEntities.IBAN_CODE]: i18next.t(
    "common.constants.guardrails.pii.ibanCode",
  ),
  [PiiSupportedEntities.IP_ADDRESS]: i18next.t(
    "common.constants.guardrails.pii.ipAddress",
  ),
  [PiiSupportedEntities.NRP]: i18next.t("common.constants.guardrails.pii.nrp"),
  [PiiSupportedEntities.LOCATION]: i18next.t(
    "common.constants.guardrails.pii.location",
  ),
  [PiiSupportedEntities.PERSON]: i18next.t(
    "common.constants.guardrails.pii.person",
  ),
  [PiiSupportedEntities.CRYPTO]: i18next.t(
    "common.constants.guardrails.pii.crypto",
  ),
  [PiiSupportedEntities.MEDICAL_LICENSE]: i18next.t(
    "common.constants.guardrails.pii.medicalLicense",
  ),
  [PiiSupportedEntities.URL]: "URL",
  [PiiSupportedEntities.DATE_TIME]: i18next.t(
    "common.constants.guardrails.pii.dateTime",
  ),
  [PiiSupportedEntities.US_BANK_NUMBER]: i18next.t(
    "common.constants.guardrails.pii.usBankNumber",
  ),
  [PiiSupportedEntities.US_DRIVER_LICENSE]: i18next.t(
    "common.constants.guardrails.pii.usDriverLicense",
  ),
  [PiiSupportedEntities.US_ITIN]: "US ITIN",
  [PiiSupportedEntities.US_PASSPORT]: i18next.t(
    "common.constants.guardrails.pii.usPassport",
  ),
  [PiiSupportedEntities.US_SSN]: "SSN",
  [PiiSupportedEntities.UK_NHS]: i18next.t(
    "common.constants.guardrails.pii.ukNhs",
  ),
  [PiiSupportedEntities.UK_NINO]: "NINO",
  [PiiSupportedEntities.UK_PASSPORT]: i18next.t(
    "common.constants.guardrails.pii.ukPassport",
  ),
  [PiiSupportedEntities.ES_NIF]: "NIF",
  [PiiSupportedEntities.ES_NIE]: "NIE",
  [PiiSupportedEntities.ES_DNI]: "DNI",
  [PiiSupportedEntities.ES_CIF]: "CIF",
  [PiiSupportedEntities.IT_FISCAL_CODE]: i18next.t(
    "common.constants.guardrails.pii.itFiscalCode",
  ),
  [PiiSupportedEntities.PL_PESEL]: "PESEL",
  [PiiSupportedEntities.PL_NIP]: "NIP",
  [PiiSupportedEntities.PL_ID]: i18next.t(
    "common.constants.guardrails.pii.plId",
  ),
  [PiiSupportedEntities.SG_NRIC_FIN]: i18next.t(
    "common.constants.guardrails.pii.sgNricFin",
  ),
  [PiiSupportedEntities.AU_ABN]: i18next.t(
    "common.constants.guardrails.pii.auAbn",
  ),
  [PiiSupportedEntities.AU_ACN]: i18next.t(
    "common.constants.guardrails.pii.auAcn",
  ),
  [PiiSupportedEntities.AU_TFN]: i18next.t(
    "common.constants.guardrails.pii.auTfn",
  ),
  [PiiSupportedEntities.IN_AADHAAR]: i18next.t(
    "common.constants.guardrails.pii.inAadhaar",
  ),
  [PiiSupportedEntities.IN_PAN]: i18next.t(
    "common.constants.guardrails.pii.inPan",
  ),
  [PiiSupportedEntities.FI_HETU]: i18next.t(
    "common.constants.guardrails.pii.fiHetu",
  ),
};

export const GuardrailNamesLabelMap = {
  [GuardrailTypes.TOPIC]: i18next.t("common.constants.guardrails.names.topic"),
  [GuardrailTypes.PII]: i18next.t("common.constants.guardrails.names.pii"),
};

export const getGuardrailComputedResult = (
  guardrails: GuardrailValidation[],
) => {
  const uniqueNames = new Set<GuardrailTypes>();
  guardrails.forEach((item) => {
    item.checks.forEach((check) => {
      uniqueNames.add(check.name);
    });
  });

  const result = {} as Record<GuardrailTypes, GuardrailResult>;

  uniqueNames.forEach((name) => {
    result[name] = GuardrailResult.PASSED;

    for (const item of guardrails) {
      for (const check of item.checks) {
        if (check.name === name && check.result === GuardrailResult.FAILED) {
          result[name] = GuardrailResult.FAILED;
          break;
        }
      }
      if (result[name] === GuardrailResult.FAILED) break;
    }
  });

  const statusList = Object.entries(result).map(([name, status]) => ({
    name,
    status,
  })) as GuardrailComputedResult[];

  let generalStatus = GuardrailResult.PASSED;
  const hasFailedGuardrails = statusList.some(
    (res) => res.status === GuardrailResult.FAILED,
  );
  if (hasFailedGuardrails) {
    generalStatus = GuardrailResult.FAILED;
  }

  return { statusList, generalStatus };
};
