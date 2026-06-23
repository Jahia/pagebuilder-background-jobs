module.exports = {
    // Babel config used by Jest (via babel-jest) to transform JSX/ESM in tests.
    // Webpack supplies its own inline presets in webpack.config.js, so this only
    // takes effect for the test environment.
    env: {
        test: {
            presets: [
                ['@babel/preset-env', {targets: {node: 'current'}}],
                ['@babel/preset-react', {runtime: 'classic'}]
            ]
        }
    }
};
