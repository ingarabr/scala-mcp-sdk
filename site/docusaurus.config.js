// @ts-check

/** @type {import('@docusaurus/types').Config} */
const config = {
  title: 'scala-mcp-sdk',
  tagline: 'Type-safe MCP servers in Scala',
  favicon: 'img/favicon.ico',

  url: 'https://ingarabr.github.io',
  baseUrl: '/scala-mcp-sdk/',

  organizationName: 'ingarabr',
  projectName: 'scala-mcp-sdk',

  onBrokenLinks: 'throw',

  markdown: {
    hooks: {
      onBrokenMarkdownLinks: 'warn',
    },
  },

  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  presets: [
    [
      'classic',
      /** @type {import('@docusaurus/preset-classic').Options} */
      ({
        docs: {
          sidebarPath: './sidebars.js',
          editUrl: 'https://github.com/ingarabr/scala-mcp-sdk/tree/main/docs-site/',
        },
        theme: {
          customCss: './src/css/custom.css',
        },
      }),
    ],
  ],

  themeConfig:
    /** @type {import('@docusaurus/preset-classic').ThemeConfig} */
    ({
      colorMode: {
        defaultMode: 'light',
        disableSwitch: false,
        respectPrefersColorScheme: true,
      },
      navbar: {
        title: 'scala-mcp-sdk',
        items: [
          {
            type: 'docSidebar',
            sidebarId: 'docsSidebar',
            position: 'left',
            label: 'Docs',
          },
          {
            href: 'https://javadoc.io/doc/com.github.ingarabr.mcp/server_3/latest',
            label: 'API Reference',
            position: 'left',
          },
          {
            href: 'https://github.com/ingarabr/scala-mcp-sdk',
            label: 'GitHub',
            position: 'right',
          },
        ],
      },
      footer: {
        style: 'dark',
        links: [
          {
            title: 'Documentation',
            items: [
              { label: 'Getting Started', to: '/docs/getting-started' },
              { label: 'Tools', to: '/docs/features/tools' },
              { label: 'Transports', to: '/docs/transports/overview' },
            ],
          },
          {
            title: 'Resources',
            items: [
              { label: 'MCP Specification', href: 'https://modelcontextprotocol.io/' },
              { label: 'GitHub', href: 'https://github.com/ingarabr/scala-mcp-sdk' },
            ],
          },
          {
            title: 'Ecosystem',
            items: [
              { label: 'Typelevel', href: 'https://typelevel.org/' },
              { label: 'Cats Effect', href: 'https://typelevel.org/cats-effect/' },
              { label: 'http4s', href: 'https://http4s.org/' },
            ],
          },
        ],
        copyright: `Copyright © ${new Date().getFullYear()} Ingar Abrahamsen. MIT License.`,
      },
      prism: {
        theme: require('prism-react-renderer').themes.github,
        darkTheme: require('prism-react-renderer').themes.dracula,
        additionalLanguages: ['java', 'scala', 'bash', 'json'],
      },
    }),
};

module.exports = config;
