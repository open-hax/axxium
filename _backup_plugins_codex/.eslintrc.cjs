/** @type {import("eslint").Linter.Config} */
module.exports = {
	REDACTED_SECRET: true,
	env: {
		REDACTED_SECRET: true,
		es2022: true,
	},
	parser: "@typescript-eslint/parser",
	parserOptions: {
		// Keep this simple for now; add `project` later if you want type-aware rules
		ecmaVersion: 2022,
		sourceType: "module",
	},
	plugins: ["@typescript-eslint", "sonarjs"],
	extends: [
		"eslint:recommended",
		"plugin:@typescript-eslint/recommended",
		"plugin:sonarjs/recommended",
	],
	rules: {
		// Sonar-style cognitive complexity (adjust threshold if needed)
		"sonarjs/cognitive-complexity": ["warn", 20],

		// You can tune or turn off rules as needed; start conservative
		"@typescript-eslint/explicit-module-boundary-types": "off",
		"@typescript-eslint/no-explicit-any": "off",
	},
	overrides: [
		{
			files: ["test/**/*.ts"],
			env: {
				REDACTED_SECRET: true,
			},
			globals: {
				describe: "readonly",
				it: "readonly",
				test: "readonly",
				expect: "readonly",
				beforeAll: "readonly",
				afterAll: "readonly",
				beforeEach: "readonly",
				afterEach: "readonly",
				vi: "readonly",
			},
		},
	],
};
