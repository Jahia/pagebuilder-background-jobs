/*
 * Prettier is deliberately configured to AGREE WITH ESLINT, which is the authority here:
 * `yarn lint` is the gate that CI and reviewers run, so the formatter conforms to it rather than
 * the other way round.
 *
 * Every option below mirrors a rule resolved from @jahia/eslint-config (verified with
 * `eslint --print-config`). Do not change one side alone -- an editor/format-on-save hook and
 * `yarn lint` will then fight each other, each undoing the other's work:
 *
 *   eslint rule                             prettier option
 *   --------------------------------------  -----------------------------
 *   semi: ["error", "always"]               semi: true
 *   comma-dangle: ["error", "never"]        trailingComma: 'none'
 *   object-curly-spacing: ["error","never"] bracketSpacing: false
 *   arrow-parens: ["error", "as-needed"]    arrowParens: 'avoid'
 *   quotes: ["error", "single"]             singleQuote: true
 *   indent: ["error", 4]                    tabWidth: 4
 *
 * tests/.prettierrc.js re-exports this file so the two trees cannot drift apart.
 */
module.exports = {
    semi: true,
    trailingComma: 'none',
    bracketSpacing: false,
    arrowParens: 'avoid',
    singleQuote: true,
    printWidth: 120,
    tabWidth: 4
};
