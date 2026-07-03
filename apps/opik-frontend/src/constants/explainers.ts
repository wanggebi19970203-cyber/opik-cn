import i18next from "i18next";
import { Explainer } from "@/types/shared";

export enum EXPLAINER_ID {
  visible_scores = "visible_scores",
  what_do_you_use_projects_for = "what_do_you_use_projects_for",
  what_are_feedback_scores = "what_are_feedback_scores",
  i_created_a_project_now_what = "i_created_a_project_now_what",
  what_are_traces = "what_are_traces",
  what_are_spans = "what_are_spans",
  what_are_threads = "what_are_threads",
  whats_online_evaluation = "whats_online_evaluation",
  i_added_traces_to_an_test_suite_now_what = "i_added_traces_to_an_test_suite_now_what",
  i_added_items_to_a_dataset_now_what = "i_added_items_to_a_dataset_now_what",
  how_to_choose_annotation_queue_type = "how_to_choose_annotation_queue_type",
  why_would_i_want_to_add_traces_to_an_test_suite = "why_would_i_want_to_add_traces_to_an_test_suite",
  hows_the_cost_estimated = "hows_the_cost_estimated",
  hows_the_thread_cost_estimated = "hows_the_thread_cost_estimated",
  whats_that_prompt_select = "whats_that_prompt_select",
  i_added_edited_a_new_online_evaluation_rule_now_what = "i_added_edited_a_new_online_evaluation_rule_now_what",
  i_added_edited_a_new_online_evaluation_thread_level_rule_now_what = "i_added_edited_a_new_online_evaluation_thread_level_rule_now_what",
  i_added_edited_a_new_online_evaluation_span_level_rule_now_what = "i_added_edited_a_new_online_evaluation_span_level_rule_now_what",
  what_are_these_elements_in_the_tree = "what_are_these_elements_in_the_tree",
  what_is_human_review = "what_is_human_review",
  whats_an_experiment = "whats_an_experiment",
  whats_a_prompt_commit = "whats_a_prompt_commit",
  what_are_experiment_items = "what_are_experiment_items",
  whats_the_experiment_configuration = "whats_the_experiment_configuration",
  what_does_it_mean_to_compare_my_experiments = "what_does_it_mean_to_compare_my_experiments",
  whats_the_test_suite_item = "whats_the_test_suite_item",
  whats_a_test_suite = "whats_a_test_suite",
  why_do_i_need_multiple_test_suites = "why_do_i_need_multiple_test_suites",
  what_format_is_this_to_add_my_test_suite_item = "what_format_is_this_to_add_my_test_suite_item",
  what_format_is_this_to_add_my_dataset_item = "what_format_is_this_to_add_my_dataset_item",
  whats_the_prompt_library = "whats_the_prompt_library",
  how_do_i_use_this_prompt = "how_do_i_use_this_prompt",
  why_do_i_have_experiments_in_the_prompt_library = "why_do_i_have_experiments_in_the_prompt_library",
  what_are_commits = "what_are_commits",
  how_do_i_write_my_prompt = "how_do_i_write_my_prompt",
  what_happens_if_i_edit_my_prompt = "what_happens_if_i_edit_my_prompt",
  whats_the_playground = "whats_the_playground",
  whats_these_configuration_things = "whats_these_configuration_things",
  why_do_i_need_an_ai_provider = "why_do_i_need_an_ai_provider",
  why_do_i_need_the_collaborators_tab = "why_do_i_need_the_collaborators_tab",
  what_does_the_test_suite_do_here = "what_does_the_test_suite_do_here",
  how_do_i_use_the_test_suite_in_the_playground = "how_do_i_use_the_test_suite_in_the_playground",
  whats_llm_as_a_judge = "whats_llm_as_a_judge",
  whats_a_code_metric = "whats_a_code_metric",
  what_are_feedback_definitions = "what_are_feedback_definitions",
  what_format_should_the_metadata_be = "what_format_should_the_metadata_be",
  what_format_should_the_prompt_be = "what_format_should_the_prompt_be",
  whats_an_optimization_run = "whats_an_optimization_run",
  whats_the_best_score = "whats_the_best_score",
  what_happens_if_i_edit_an_ai_provider = "what_happens_if_i_edit_an_ai_provider",
  what_happens_if_i_edit_a_rule = "what_happens_if_i_edit_a_rule",
  what_happens_if_i_edit_a_thread_rule = "what_happens_if_i_edit_a_thread_rule",
  what_happens_if_i_edit_a_feedback_definition = "what_happens_if_i_edit_a_feedback_definition",
  why_would_i_want_to_create_a_new_project = "why_would_i_want_to_create_a_new_project",
  what_are_annotation_queues = "what_are_annotation_queues",
  whats_the_commit_history = "whats_the_commit_history",
  why_would_i_compare_commits = "why_would_i_compare_commits",
  whats_the_optimizer = "whats_the_optimizer",
  what_are_trial_items = "what_are_trial_items",
  whats_the_evaluation_run_configuration = "whats_the_evaluation_run_configuration",
  metric_equals = "metric_equals",
  metric_contains = "metric_contains",
  metric_regex_match = "metric_regex_match",
  metric_is_json = "metric_is_json",
  metric_levenshtein = "metric_levenshtein",
  metric_sentence_bleu = "metric_sentence_bleu",
  metric_corpus_bleu = "metric_corpus_bleu",
  metric_rouge = "metric_rouge",
  metric_hallucination = "metric_hallucination",
  metric_g_eval = "metric_g_eval",
  metric_moderation = "metric_moderation",
  metric_usefulness = "metric_usefulness",
  metric_answer_relevance = "metric_answer_relevance",
  metric_context_precision = "metric_context_precision",
  metric_context_recall = "metric_context_recall",
  trace_opik_ai = "trace_opik_ai",
  feedback_scores_hotkeys = "feedback_scores_hotkeys",
  llm_judge_variable_mapping = "llm_judge_variable_mapping",
  prompt_generation_learn_more = "prompt_generation_learn_more",
  prompt_improvement_learn_more = "prompt_improvement_learn_more",
  prompt_improvement_optimizer = "prompt_improvement_optimizer",
  whats_an_alert = "whats_an_alert",
  what_are_dashboards = "what_are_dashboards",
  whats_the_optimization_config = "whats_the_optimization_config",
  whats_the_algorithm_section = "whats_the_algorithm_section",
  whats_the_test_suite_section = "whats_the_test_suite_section",
  whats_the_metric_section = "whats_the_metric_section",
  whats_the_metric_settings = "whats_the_metric_settings",
  whats_the_algorithm_settings = "whats_the_algorithm_settings",
  // Metric config explainers
  geval_task_introduction = "geval_task_introduction",
  geval_evaluation_criteria = "geval_evaluation_criteria",
  metric_reference_key = "metric_reference_key",
  metric_case_sensitive = "metric_case_sensitive",
  // Optimizer config explainers
  optimizer_verbose = "optimizer_verbose",
  optimizer_adaptive_mutation = "optimizer_adaptive_mutation",
  optimizer_enable_moo = "optimizer_enable_moo",
  optimizer_enable_llm_crossover = "optimizer_enable_llm_crossover",
  optimizer_output_style_guidance = "optimizer_output_style_guidance",
  optimizer_infer_output_style = "optimizer_infer_output_style",
  // Dashboard widget explainers
  feedback_score_groupby_requires_single_metric = "feedback_score_groupby_requires_single_metric",
  duration_groupby_requires_single_metric = "duration_groupby_requires_single_metric",
  usage_groupby_requires_single_metric = "usage_groupby_requires_single_metric",
  groupby_requires_metric = "groupby_requires_metric",
}

export const EXPLAINERS_MAP: Record<EXPLAINER_ID, Explainer> = {
  [EXPLAINER_ID.visible_scores]: {
    id: EXPLAINER_ID.visible_scores,
    description: i18next.t("common.constants.explainers.visibleScores"),
  },
  [EXPLAINER_ID.what_do_you_use_projects_for]: {
    id: EXPLAINER_ID.what_do_you_use_projects_for,
    description: i18next.t("common.constants.explainers.whatDoYouUseProjectsFor"),
  },
  [EXPLAINER_ID.what_are_feedback_scores]: {
    id: EXPLAINER_ID.what_are_feedback_scores,
    description: i18next.t("common.constants.explainers.whatAreFeedbackScores"),
  },
  [EXPLAINER_ID.i_created_a_project_now_what]: {
    id: EXPLAINER_ID.i_created_a_project_now_what,
    title: i18next.t("common.constants.explainers.projectCreated"),
    description: i18next.t("common.constants.explainers.iCreatedAProjectNowWhat"),
  },
  [EXPLAINER_ID.what_are_traces]: {
    id: EXPLAINER_ID.what_are_traces,
    description: i18next.t("common.constants.explainers.whatAreTraces"),
  },
  [EXPLAINER_ID.what_are_spans]: {
    id: EXPLAINER_ID.what_are_spans,
    description: i18next.t("common.constants.explainers.whatAreSpans"),
  },
  [EXPLAINER_ID.what_are_threads]: {
    id: EXPLAINER_ID.what_are_threads,
    description: i18next.t("common.constants.explainers.whatAreThreads"),
    type: "help",
  },
  [EXPLAINER_ID.whats_online_evaluation]: {
    id: EXPLAINER_ID.whats_online_evaluation,
    description: i18next.t("common.constants.explainers.whatsOnlineEvaluation"),
  },
  [EXPLAINER_ID.i_added_traces_to_an_test_suite_now_what]: {
    id: EXPLAINER_ID.i_added_traces_to_an_test_suite_now_what,
    title: i18next.t("common.constants.explainers.tracesAddedToTestSuite"),
    description: i18next.t("common.constants.explainers.iAddedTracesToATestSuiteNowWhat"),
  },
  [EXPLAINER_ID.i_added_items_to_a_dataset_now_what]: {
    id: EXPLAINER_ID.i_added_items_to_a_dataset_now_what,
    title: i18next.t("common.constants.explainers.itemsAddedToDataset"),
    description: i18next.t("common.constants.explainers.iAddedItemsToADatasetNowWhat"),
  },
  [EXPLAINER_ID.why_would_i_want_to_add_traces_to_an_test_suite]: {
    id: EXPLAINER_ID.why_would_i_want_to_add_traces_to_an_test_suite,
    description: i18next.t("common.constants.explainers.whyWouldIWantToAddTracesToATestSuite"),
  },
  [EXPLAINER_ID.hows_the_cost_estimated]: {
    id: EXPLAINER_ID.hows_the_cost_estimated,
    description: i18next.t("common.constants.explainers.howsTheCostEstimated"),
    type: "help",
  },
  [EXPLAINER_ID.hows_the_thread_cost_estimated]: {
    id: EXPLAINER_ID.hows_the_thread_cost_estimated,
    description: i18next.t("common.constants.explainers.howsTheThreadCostEstimated"),
    type: "help",
  },
  [EXPLAINER_ID.whats_that_prompt_select]: {
    id: EXPLAINER_ID.whats_that_prompt_select,
    description: i18next.t("common.constants.explainers.whatsThatPromptSelect"),
    type: "help",
  },
  [EXPLAINER_ID.i_added_edited_a_new_online_evaluation_rule_now_what]: {
    id: EXPLAINER_ID.i_added_edited_a_new_online_evaluation_rule_now_what,
    title: i18next.t("common.constants.explainers.evaluationRuleSet"),
    description: i18next.t("common.constants.explainers.iAddedEditedANewOnlineEvaluationRuleNowWhat"),
  },
  [EXPLAINER_ID.i_added_edited_a_new_online_evaluation_thread_level_rule_now_what]:
    {
      id: EXPLAINER_ID.i_added_edited_a_new_online_evaluation_thread_level_rule_now_what,
      title: i18next.t("common.constants.explainers.evaluationRuleSet"),
      description: i18next.t("common.constants.explainers.iAddedEditedANewOnlineEvaluationThreadLevelRuleNowWhat"),
    },
  [EXPLAINER_ID.i_added_edited_a_new_online_evaluation_span_level_rule_now_what]:
    {
      id: EXPLAINER_ID.i_added_edited_a_new_online_evaluation_span_level_rule_now_what,
      title: i18next.t("common.constants.explainers.evaluationRuleSet"),
      description: i18next.t("common.constants.explainers.iAddedEditedANewOnlineEvaluationSpanLevelRuleNowWhat"),
    },
  [EXPLAINER_ID.what_are_these_elements_in_the_tree]: {
    id: EXPLAINER_ID.what_are_these_elements_in_the_tree,
    description: i18next.t("common.constants.explainers.whatAreTheseElementsInTheTree"),
  },
  [EXPLAINER_ID.what_is_human_review]: {
    id: EXPLAINER_ID.what_is_human_review,
    description: i18next.t("common.constants.explainers.whatIsHumanReview"),
  },
  [EXPLAINER_ID.what_are_annotation_queues]: {
    id: EXPLAINER_ID.what_are_annotation_queues,
    description: i18next.t("common.constants.explainers.whatAreAnnotationQueues"),
  },
  [EXPLAINER_ID.how_to_choose_annotation_queue_type]: {
    id: EXPLAINER_ID.how_to_choose_annotation_queue_type,
    description: i18next.t("common.constants.explainers.howToChooseAnnotationQueueType"),
  },
  [EXPLAINER_ID.whats_an_experiment]: {
    id: EXPLAINER_ID.whats_an_experiment,
    description: i18next.t("common.constants.explainers.whatsAnExperiment"),
  },
  [EXPLAINER_ID.whats_a_prompt_commit]: {
    id: EXPLAINER_ID.whats_a_prompt_commit,
    description: i18next.t("common.constants.explainers.whatsAPromptCommit"),
    type: "help",
  },
  [EXPLAINER_ID.what_are_experiment_items]: {
    id: EXPLAINER_ID.what_are_experiment_items,
    description: i18next.t("common.constants.explainers.whatAreExperimentItems"),
  },
  [EXPLAINER_ID.whats_the_experiment_configuration]: {
    id: EXPLAINER_ID.whats_the_experiment_configuration,
    description: i18next.t("common.constants.explainers.whatsTheExperimentConfiguration"),
  },
  [EXPLAINER_ID.what_does_it_mean_to_compare_my_experiments]: {
    id: EXPLAINER_ID.what_does_it_mean_to_compare_my_experiments,
    description: i18next.t("common.constants.explainers.whatDoesItMeanToCompareMyExperiments"),
    type: "help",
  },
  [EXPLAINER_ID.whats_the_test_suite_item]: {
    id: EXPLAINER_ID.whats_the_test_suite_item,
    description: i18next.t("common.constants.explainers.whatsTheTestSuiteItem"),
    type: "help",
  },
  [EXPLAINER_ID.whats_a_test_suite]: {
    id: EXPLAINER_ID.whats_a_test_suite,
    description: i18next.t("common.constants.explainers.whatsATestSuite"),
  },
  [EXPLAINER_ID.why_do_i_need_multiple_test_suites]: {
    id: EXPLAINER_ID.why_do_i_need_multiple_test_suites,
    description: i18next.t("common.constants.explainers.whyDoINeedMultipleTestSuites"),
  },
  [EXPLAINER_ID.what_format_is_this_to_add_my_test_suite_item]: {
    id: EXPLAINER_ID.what_format_is_this_to_add_my_test_suite_item,
    description: i18next.t("common.constants.explainers.whatFormatIsThisToAddMyTestSuiteItem"),
  },
  [EXPLAINER_ID.what_format_is_this_to_add_my_dataset_item]: {
    id: EXPLAINER_ID.what_format_is_this_to_add_my_dataset_item,
    description: i18next.t("common.constants.explainers.whatFormatIsThisToAddMyDatasetItem"),
  },
  [EXPLAINER_ID.whats_the_prompt_library]: {
    id: EXPLAINER_ID.whats_the_prompt_library,
    description: i18next.t("common.constants.explainers.whatsThePromptLibrary"),
  },
  [EXPLAINER_ID.how_do_i_use_this_prompt]: {
    id: EXPLAINER_ID.how_do_i_use_this_prompt,
    description: i18next.t("common.constants.explainers.howDoIUseThisPrompt"),
  },
  [EXPLAINER_ID.why_do_i_have_experiments_in_the_prompt_library]: {
    id: EXPLAINER_ID.why_do_i_have_experiments_in_the_prompt_library,
    description: i18next.t("common.constants.explainers.whyDoIHaveExperimentsInThePromptLibrary"),
  },
  [EXPLAINER_ID.what_are_commits]: {
    id: EXPLAINER_ID.what_are_commits,
    description: i18next.t("common.constants.explainers.whatAreCommits"),
    type: "help",
  },
  [EXPLAINER_ID.how_do_i_write_my_prompt]: {
    id: EXPLAINER_ID.how_do_i_write_my_prompt,
    description: i18next.t("common.constants.explainers.howDoIWriteMyPrompt"),
  },
  [EXPLAINER_ID.what_happens_if_i_edit_my_prompt]: {
    id: EXPLAINER_ID.what_happens_if_i_edit_my_prompt,
    description: i18next.t("common.constants.explainers.whatHappensIfIEditMyPrompt"),
  },
  [EXPLAINER_ID.whats_the_playground]: {
    id: EXPLAINER_ID.whats_the_playground,
    description: i18next.t("common.constants.explainers.whatsThePlayground"),
  },
  [EXPLAINER_ID.whats_these_configuration_things]: {
    id: EXPLAINER_ID.whats_these_configuration_things,
    title: i18next.t("common.constants.explainers.modelParameters"),
    description: i18next.t("common.constants.explainers.whatsTheseConfigurationThings"),
  },
  [EXPLAINER_ID.why_do_i_need_an_ai_provider]: {
    id: EXPLAINER_ID.why_do_i_need_an_ai_provider,
    description: i18next.t("common.constants.explainers.whyDoINeedAnAiProvider"),
  },
  [EXPLAINER_ID.what_does_the_test_suite_do_here]: {
    id: EXPLAINER_ID.what_does_the_test_suite_do_here,
    description: i18next.t("common.constants.explainers.whatDoesTheTestSuiteDoHere"),
  },
  [EXPLAINER_ID.how_do_i_use_the_test_suite_in_the_playground]: {
    id: EXPLAINER_ID.how_do_i_use_the_test_suite_in_the_playground,
    description: i18next.t("common.constants.explainers.howDoIUseTheTestSuiteInThePlayground"),
  },
  [EXPLAINER_ID.whats_llm_as_a_judge]: {
    id: EXPLAINER_ID.whats_llm_as_a_judge,
    description: i18next.t("common.constants.explainers.whatsLlmAsAJudge"),
  },
  [EXPLAINER_ID.whats_a_code_metric]: {
    id: EXPLAINER_ID.whats_a_code_metric,
    description: i18next.t("common.constants.explainers.whatsACodeMetric"),
  },
  [EXPLAINER_ID.what_are_feedback_definitions]: {
    id: EXPLAINER_ID.what_are_feedback_definitions,
    description: i18next.t("common.constants.explainers.whatAreFeedbackDefinitions"),
  },
  [EXPLAINER_ID.why_do_i_need_the_collaborators_tab]: {
    id: EXPLAINER_ID.why_do_i_need_the_collaborators_tab,
    description: i18next.t("common.constants.explainers.whyDoINeedTheCollaboratorsTab"),
  },
  [EXPLAINER_ID.what_format_should_the_metadata_be]: {
    id: EXPLAINER_ID.what_format_should_the_metadata_be,
    description: i18next.t("common.constants.explainers.whatFormatShouldTheMetadataBe"),
  },
  [EXPLAINER_ID.what_format_should_the_prompt_be]: {
    id: EXPLAINER_ID.what_format_should_the_prompt_be,
    description: i18next.t("common.constants.explainers.whatFormatShouldThePromptBe"),
  },
  [EXPLAINER_ID.whats_an_optimization_run]: {
    id: EXPLAINER_ID.whats_an_optimization_run,
    description: i18next.t("common.constants.explainers.whatsAnOptimizationRun"),
  },
  [EXPLAINER_ID.whats_the_best_score]: {
    id: EXPLAINER_ID.whats_the_best_score,
    description: i18next.t("common.constants.explainers.whatsTheBestScore"),
  },
  [EXPLAINER_ID.what_happens_if_i_edit_an_ai_provider]: {
    id: EXPLAINER_ID.what_happens_if_i_edit_an_ai_provider,
    title: i18next.t("common.constants.explainers.editingAnExistingKey"),
    description: i18next.t("common.constants.explainers.whatHappensIfIEditAnAiProvider"),
  },
  [EXPLAINER_ID.what_happens_if_i_edit_a_rule]: {
    id: EXPLAINER_ID.what_happens_if_i_edit_a_rule,
    title: i18next.t("common.constants.explainers.editingAnExistingRule"),
    description: i18next.t("common.constants.explainers.whatHappensIfIEditARule"),
  },
  [EXPLAINER_ID.what_happens_if_i_edit_a_thread_rule]: {
    id: EXPLAINER_ID.what_happens_if_i_edit_a_thread_rule,
    title: i18next.t("common.constants.explainers.editingAThreadLevelRule"),
    description: i18next.t("common.constants.explainers.whatHappensIfIEditAThreadRule"),
  },
  [EXPLAINER_ID.what_happens_if_i_edit_a_feedback_definition]: {
    id: EXPLAINER_ID.what_happens_if_i_edit_a_feedback_definition,
    title: i18next.t("common.constants.explainers.editingAFeedbackDefinition"),
    description: i18next.t("common.constants.explainers.whatHappensIfIEditAFeedbackDefinition"),
  },
  [EXPLAINER_ID.why_would_i_want_to_create_a_new_project]: {
    id: EXPLAINER_ID.why_would_i_want_to_create_a_new_project,
    description: i18next.t("common.constants.explainers.whyWouldIWantToCreateANewProject"),
  },
  [EXPLAINER_ID.whats_the_commit_history]: {
    id: EXPLAINER_ID.whats_the_commit_history,
    description: i18next.t("common.constants.explainers.whatsTheCommitHistory"),
  },
  [EXPLAINER_ID.why_would_i_compare_commits]: {
    id: EXPLAINER_ID.why_would_i_compare_commits,
    description: i18next.t("common.constants.explainers.whyWouldICompareCommits"),
    type: "help",
  },
  [EXPLAINER_ID.whats_the_optimizer]: {
    id: EXPLAINER_ID.whats_the_optimizer,
    description: i18next.t("common.constants.explainers.whatsTheOptimizer"),
    type: "help",
  },
  [EXPLAINER_ID.what_are_trial_items]: {
    id: EXPLAINER_ID.what_are_trial_items,
    description: i18next.t("common.constants.explainers.whatAreTrialItems"),
  },
  [EXPLAINER_ID.whats_the_evaluation_run_configuration]: {
    id: EXPLAINER_ID.whats_the_evaluation_run_configuration,
    description: i18next.t("common.constants.explainers.whatsTheEvaluationRunConfiguration"),
  },
  [EXPLAINER_ID.metric_equals]: {
    id: EXPLAINER_ID.metric_equals,
    description: i18next.t("common.constants.explainers.metricEquals"),
  },
  [EXPLAINER_ID.metric_contains]: {
    id: EXPLAINER_ID.metric_contains,
    description: i18next.t("common.constants.explainers.metricContains"),
  },
  [EXPLAINER_ID.metric_regex_match]: {
    id: EXPLAINER_ID.metric_regex_match,
    description: i18next.t("common.constants.explainers.metricRegexMatch"),
  },
  [EXPLAINER_ID.metric_is_json]: {
    id: EXPLAINER_ID.metric_is_json,
    description: i18next.t("common.constants.explainers.metricIsJson"),
  },
  [EXPLAINER_ID.metric_levenshtein]: {
    id: EXPLAINER_ID.metric_levenshtein,
    description: i18next.t("common.constants.explainers.metricLevenshtein"),
  },
  [EXPLAINER_ID.metric_sentence_bleu]: {
    id: EXPLAINER_ID.metric_sentence_bleu,
    description: i18next.t("common.constants.explainers.metricSentenceBleu"),
  },
  [EXPLAINER_ID.metric_corpus_bleu]: {
    id: EXPLAINER_ID.metric_corpus_bleu,
    description: i18next.t("common.constants.explainers.metricCorpusBleu"),
  },
  [EXPLAINER_ID.metric_rouge]: {
    id: EXPLAINER_ID.metric_rouge,
    description: i18next.t("common.constants.explainers.metricRouge"),
  },
  [EXPLAINER_ID.metric_hallucination]: {
    id: EXPLAINER_ID.metric_hallucination,
    description: i18next.t("common.constants.explainers.metricHallucination"),
  },
  [EXPLAINER_ID.metric_g_eval]: {
    id: EXPLAINER_ID.metric_g_eval,
    description: i18next.t("common.constants.explainers.metricGEval"),
  },
  [EXPLAINER_ID.metric_moderation]: {
    id: EXPLAINER_ID.metric_moderation,
    description: i18next.t("common.constants.explainers.metricModeration"),
  },
  [EXPLAINER_ID.metric_usefulness]: {
    id: EXPLAINER_ID.metric_usefulness,
    description: i18next.t("common.constants.explainers.metricUsefulness"),
  },
  [EXPLAINER_ID.metric_answer_relevance]: {
    id: EXPLAINER_ID.metric_answer_relevance,
    description: i18next.t("common.constants.explainers.metricAnswerRelevance"),
  },
  [EXPLAINER_ID.metric_context_precision]: {
    id: EXPLAINER_ID.metric_context_precision,
    description: i18next.t("common.constants.explainers.metricContextPrecision"),
  },
  [EXPLAINER_ID.metric_context_recall]: {
    id: EXPLAINER_ID.metric_context_recall,
    description: i18next.t("common.constants.explainers.metricContextRecall"),
  },
  [EXPLAINER_ID.trace_opik_ai]: {
    id: EXPLAINER_ID.trace_opik_ai,
    description: i18next.t("common.constants.explainers.traceOpikAi"),
  },
  [EXPLAINER_ID.feedback_scores_hotkeys]: {
    id: EXPLAINER_ID.feedback_scores_hotkeys,
    description: i18next.t("common.constants.explainers.feedbackScoresHotkeys"),
    type: "help",
  },
  [EXPLAINER_ID.llm_judge_variable_mapping]: {
    id: EXPLAINER_ID.llm_judge_variable_mapping,
    description: i18next.t("common.constants.explainers.llmJudgeVariableMapping"),
  },
  [EXPLAINER_ID.prompt_generation_learn_more]: {
    id: EXPLAINER_ID.prompt_generation_learn_more,
    description: i18next.t("common.constants.explainers.promptGenerationLearnMore"),
  },
  [EXPLAINER_ID.prompt_improvement_learn_more]: {
    id: EXPLAINER_ID.prompt_improvement_learn_more,
    description: i18next.t("common.constants.explainers.promptImprovementLearnMore"),
  },
  [EXPLAINER_ID.prompt_improvement_optimizer]: {
    id: EXPLAINER_ID.prompt_improvement_optimizer,
    description: i18next.t("common.constants.explainers.promptImprovementOptimizer"),
  },
  [EXPLAINER_ID.whats_an_alert]: {
    id: EXPLAINER_ID.whats_an_alert,
    description: i18next.t("common.constants.explainers.whatsAnAlert"),
  },
  [EXPLAINER_ID.what_are_dashboards]: {
    id: EXPLAINER_ID.what_are_dashboards,
    description: i18next.t("common.constants.explainers.whatAreDashboards"),
  },
  [EXPLAINER_ID.whats_the_optimization_config]: {
    id: EXPLAINER_ID.whats_the_optimization_config,
    description: i18next.t("common.constants.explainers.whatsTheOptimizationConfig"),
  },
  [EXPLAINER_ID.whats_the_algorithm_section]: {
    id: EXPLAINER_ID.whats_the_algorithm_section,
    description: i18next.t("common.constants.explainers.whatsTheAlgorithmSection"),
  },
  [EXPLAINER_ID.whats_the_test_suite_section]: {
    id: EXPLAINER_ID.whats_the_test_suite_section,
    description: i18next.t("common.constants.explainers.whatsTheTestSuiteSection"),
  },
  [EXPLAINER_ID.whats_the_metric_section]: {
    id: EXPLAINER_ID.whats_the_metric_section,
    description: i18next.t("common.constants.explainers.whatsTheMetricSection"),
  },
  [EXPLAINER_ID.whats_the_metric_settings]: {
    id: EXPLAINER_ID.whats_the_metric_settings,
    description: i18next.t("common.constants.explainers.whatsTheMetricSettings"),
  },
  [EXPLAINER_ID.whats_the_algorithm_settings]: {
    id: EXPLAINER_ID.whats_the_algorithm_settings,
    description: i18next.t("common.constants.explainers.whatsTheAlgorithmSettings"),
  },
  [EXPLAINER_ID.geval_task_introduction]: {
    id: EXPLAINER_ID.geval_task_introduction,
    description: i18next.t("common.constants.explainers.gevalTaskIntroduction"),
  },
  [EXPLAINER_ID.geval_evaluation_criteria]: {
    id: EXPLAINER_ID.geval_evaluation_criteria,
    description: i18next.t("common.constants.explainers.gevalEvaluationCriteria"),
  },
  [EXPLAINER_ID.metric_reference_key]: {
    id: EXPLAINER_ID.metric_reference_key,
    description: i18next.t("common.constants.explainers.metricReferenceKey"),
  },
  [EXPLAINER_ID.metric_case_sensitive]: {
    id: EXPLAINER_ID.metric_case_sensitive,
    description: i18next.t("common.constants.explainers.metricCaseSensitive"),
  },
  [EXPLAINER_ID.optimizer_verbose]: {
    id: EXPLAINER_ID.optimizer_verbose,
    description: i18next.t("common.constants.explainers.optimizerVerbose"),
  },
  [EXPLAINER_ID.optimizer_adaptive_mutation]: {
    id: EXPLAINER_ID.optimizer_adaptive_mutation,
    description: i18next.t("common.constants.explainers.optimizerAdaptiveMutation"),
  },
  [EXPLAINER_ID.optimizer_enable_moo]: {
    id: EXPLAINER_ID.optimizer_enable_moo,
    description: i18next.t("common.constants.explainers.optimizerEnableMoo"),
  },
  [EXPLAINER_ID.optimizer_enable_llm_crossover]: {
    id: EXPLAINER_ID.optimizer_enable_llm_crossover,
    description: i18next.t("common.constants.explainers.optimizerEnableLlmCrossover"),
  },
  [EXPLAINER_ID.optimizer_output_style_guidance]: {
    id: EXPLAINER_ID.optimizer_output_style_guidance,
    description: i18next.t("common.constants.explainers.optimizerOutputStyleGuidance"),
  },
  [EXPLAINER_ID.optimizer_infer_output_style]: {
    id: EXPLAINER_ID.optimizer_infer_output_style,
    description: i18next.t("common.constants.explainers.optimizerInferOutputStyle"),
  },
  [EXPLAINER_ID.feedback_score_groupby_requires_single_metric]: {
    id: EXPLAINER_ID.feedback_score_groupby_requires_single_metric,
    description: i18next.t("common.constants.explainers.feedbackScoreGroupbyRequiresSingleMetric"),
  },
  [EXPLAINER_ID.duration_groupby_requires_single_metric]: {
    id: EXPLAINER_ID.duration_groupby_requires_single_metric,
    description: i18next.t("common.constants.explainers.durationGroupbyRequiresSingleMetric"),
  },
  [EXPLAINER_ID.usage_groupby_requires_single_metric]: {
    id: EXPLAINER_ID.usage_groupby_requires_single_metric,
    description: i18next.t("common.constants.explainers.usageGroupbyRequiresSingleMetric"),
  },
  [EXPLAINER_ID.groupby_requires_metric]: {
    id: EXPLAINER_ID.groupby_requires_metric,
    description: i18next.t("common.constants.explainers.groupbyRequiresMetric"),
  },
};
