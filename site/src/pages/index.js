import React from 'react';
import clsx from 'clsx';
import Link from '@docusaurus/Link';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import Layout from '@theme/Layout';
import styles from './index.module.css';

function HomepageHeader() {
  const {siteConfig} = useDocusaurusContext();
  return (
    <header className={clsx('hero', styles.heroBanner)}>
      <div className="container">
        <h1 className="hero__title">{siteConfig.title}</h1>
        <p className="hero__subtitle">{siteConfig.tagline}</p>
        <div className={styles.buttons}>
          <Link
            className="button button--primary button--lg"
            to="/docs/getting-started">
            Get Started
          </Link>
          <Link
            className="button button--secondary button--lg"
            href="https://github.com/ingarabr/scala-mcp-sdk">
            GitHub
          </Link>
        </div>
      </div>
    </header>
  );
}

const features = [
  {
    title: 'Protocol Library',
    description: 'We handle JSON-RPC and MCP protocol details. You bring your own HTTP server, logging, and configuration.',
  },
  {
    title: 'Typelevel Ecosystem',
    description: 'Built on Cats Effect 3, fs2, http4s, and Circe. Pure functional, composable, and type-safe.',
  },
  {
    title: 'Full MCP Support',
    description: 'Tools, resources, prompts, sampling, elicitation, and both stdio and HTTP transports.',
  },
];

function Feature({title, description}) {
  return (
    <div className={clsx('col col--4')}>
      <div className="padding-horiz--md padding-vert--lg">
        <h3>{title}</h3>
        <p>{description}</p>
      </div>
    </div>
  );
}

function HomepageFeatures() {
  return (
    <section className={styles.features}>
      <div className="container">
        <div className="row">
          {features.map((props, idx) => (
            <Feature key={idx} {...props} />
          ))}
        </div>
      </div>
    </section>
  );
}

function HomepageExample() {
  return (
    <section className={styles.example}>
      <div className="container">
        <h2>Quick Example</h2>
        <pre className={styles.codeBlock}>
{`import cats.effect.*
import mcp.protocol.*
import mcp.server.*

object MyServer extends IOApp.Simple:
  val greetTool = ToolDef.unstructured[IO, GreetInput](
    name = "greet",
    description = Some("Greet someone")
  ) { (input, ctx) =>
    IO.pure(List(Content.Text(s"Hello, \${input.name}!")))
  }

  def run: IO[Unit] =
    (for
      server <- McpServer[IO](
        info = Implementation("my-server", "1.0.0"),
        tools = List(greetTool)
      )
      transport <- StdioTransport[IO]()
      _ <- server.serve(transport)
    yield ()).useForever`}
        </pre>
      </div>
    </section>
  );
}

export default function Home() {
  const {siteConfig} = useDocusaurusContext();
  return (
    <Layout
      title={siteConfig.title}
      description="Type-safe MCP servers in Scala">
      <HomepageHeader />
      <main>
        <HomepageFeatures />
        <HomepageExample />
      </main>
    </Layout>
  );
}
