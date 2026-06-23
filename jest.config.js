module.exports = {
    testEnvironment: 'jsdom',
    setupFilesAfterEnv: ['@testing-library/jest-dom/extend-expect'],
    moduleNameMapper: {
        '\\.(scss|css)$': '<rootDir>/__mocks__/styleMock.js'
    },
    testMatch: ['**/?(*.)+(spec|test).[jt]s?(x)'],
    testPathIgnorePatterns: ['/node_modules/', '/target/'],
    modulePathIgnorePatterns: ['<rootDir>/target/', '<rootDir>/src/main/resources/javascript/apps/'],
    collectCoverageFrom: [
        'src/javascript/**/*.{js,jsx}',
        '!src/javascript/**/*.gql-*.js'
    ],
    coverageReporters: ['lcov', 'text-summary']
};
