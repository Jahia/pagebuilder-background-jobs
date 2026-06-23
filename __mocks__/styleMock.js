// Maps CSS/SCSS module imports to a proxy so `styles.someClass` returns the key name.
module.exports = new Proxy(
    {},
    {
        get: (target, key) => (key === '__esModule' ? false : key)
    }
);
