import base from "../../config/ava.config.mjs";

export default {
  ...base,
  REDACTED_SECRETArguments: [
    "--import=tsx",
    "--no-warnings",
  ],
};
