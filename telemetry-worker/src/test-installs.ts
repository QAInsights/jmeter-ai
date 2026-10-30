export const TEST_ONLY_FEATURES = ["tool_boom", "tool_nullish", "tool_echo"] as const;

export const TEST_INSTALLS = `SELECT install_id FROM feature_counts WHERE feature IN (${
	TEST_ONLY_FEATURES.map(f => `'${f}'`).join(", ")
})`;
