const path = require('path');
const {CleanWebpackPlugin} = require('clean-webpack-plugin');
const CopyWebpackPlugin = require('copy-webpack-plugin');
const ModuleFederationPlugin = require('webpack/lib/container/ModuleFederationPlugin');
const getModuleFederationConfig = require('@jahia/webpack-config/getModuleFederationConfig');
const packageJson = require('./package.json');

module.exports = (env, argv) => {
    const config = {
        entry: {
            pageBuilderBackgroundJobs: [path.resolve(__dirname, 'src/javascript/index.js')]
        },
        output: {
            path: path.resolve(__dirname, 'src/main/resources/javascript/apps/'),
            filename: '[name].bundle.js',
            chunkFilename: '[name].pageBuilderBackgroundJobs.[chunkhash:6].js',
            publicPath: (argv.mode === 'production'
                ? '/modules/pagebuilder-background-jobs/javascript/apps/'
                : 'http://localhost:8080/modules/pagebuilder-background-jobs/javascript/apps/')
        },
        resolve: {
            mainFields: ['module', 'main'],
            extensions: ['.mjs', '.js', '.jsx', '.json'],
            alias: {
                'process/browser$': require.resolve('process/browser.js'),
                'process$': require.resolve('process/browser.js')
            },
            fallback: {
                stream: require.resolve('stream-browserify'),
                util: require.resolve('util/'),
                buffer: require.resolve('buffer/'),
                process: require.resolve('process/browser.js'),
                'process/browser': require.resolve('process/browser.js')
            }
        },
        cache: {
            type: 'filesystem'
        },
        module: {
            rules: [
                {
                    test: /\.m?js$/,
                    type: 'javascript/auto',
                    resolve: {
                        fullySpecified: false
                    }
                },
                {
                    test: /\.jsx?$/,
                    include: [path.join(__dirname, 'src')],
                    use: {
                        loader: 'babel-loader',
                        options: {
                            presets: [
                                ['@babel/preset-env', {
                                    modules: false,
                                    targets: {chrome: '60', edge: '44', firefox: '54', safari: '12'}
                                }],
                                '@babel/preset-react'
                            ]
                        }
                    }
                }
            ]
        },
        plugins: [
            new ModuleFederationPlugin(getModuleFederationConfig(packageJson)),
            new CleanWebpackPlugin({verbose: false}),
            new CopyWebpackPlugin({patterns: [{from: './package.json', to: ''}]}),
            new (require('webpack').ProvidePlugin)({
                Buffer: ['buffer', 'Buffer'],
                process: 'process/browser'
            })
        ],
        mode: 'development'
    };

    config.devtool = (argv.mode === 'production') ? 'source-map' : 'eval-source-map';
    return config;
};
