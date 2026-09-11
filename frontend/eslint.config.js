import eslint from '@eslint/js'
import tsParser from '@typescript-eslint/parser'
import tsPlugin from '@typescript-eslint/eslint-plugin'
import vue from 'eslint-plugin-vue'
import vueParser from 'vue-eslint-parser'

const browserGlobals = {
  Blob: 'readonly',
  CustomEvent: 'readonly',
  Event: 'readonly',
  File: 'readonly',
  FormData: 'readonly',
  HTMLInputElement: 'readonly',
  RequestInit: 'readonly',
  URL: 'readonly',
  URLSearchParams: 'readonly',
  console: 'readonly',
  document: 'readonly',
  fetch: 'readonly',
  localStorage: 'readonly',
  window: 'readonly',
}

export default [
  { ignores: ['dist/**', 'node_modules/**'] },
  eslint.configs.recommended,
  ...vue.configs['flat/recommended'],
  {
    files: ['**/*.{ts,vue}'],
    languageOptions: {
      parser: vueParser,
      parserOptions: { parser: tsParser, extraFileExtensions: ['.vue'], sourceType: 'module' },
      globals: browserGlobals,
    },
    plugins: { '@typescript-eslint': tsPlugin },
    rules: {
      'vue/multi-word-component-names': 'off',
      'no-unused-vars': 'off',
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
      'vue/html-self-closing': 'off',
      'vue/max-attributes-per-line': 'off',
      'vue/singleline-html-element-content-newline': 'off',
      'vue/html-closing-bracket-newline': 'off',
    },
  },
]
