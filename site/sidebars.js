// @ts-check

/** @type {import('@docusaurus/plugin-content-docs').SidebarsConfig} */
const sidebars = {
  docsSidebar: [
    'intro',
    'getting-started',
    {
      type: 'category',
      label: 'Features',
      items: [
        'features/tools',
        'features/resources',
        'features/prompts',
      ],
    },
    {
      type: 'category',
      label: 'Transports',
      items: [
        'transports/overview',
        'transports/stdio',
        'transports/http',
      ],
    },
    {
      type: 'category',
      label: 'Client Capabilities',
      items: [
        'advanced/sampling',
        'advanced/elicitation',
        'advanced/roots',
      ],
    },
    'contributing',
  ],
};

module.exports = sidebars;
