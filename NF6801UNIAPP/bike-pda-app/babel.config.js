module.exports = {
  presets: [['@vue/cli-plugin-babel/preset', { modules: 'auto', useBuiltIns: 'entry' }]],
  plugins: ['@babel/plugin-transform-runtime', '@babel/plugin-proposal-optional-chaining', '@babel/plugin-proposal-nullish-coalescing-operator']
}
