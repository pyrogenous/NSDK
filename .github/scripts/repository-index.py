"""Writes index.html at the root of the Maven repository (build/repo): what it is, how to use it, and every artifact
with its versions. GitHub Pages has no directory listing, so without it https://libraries.nitea.cc/ is a 404."""
import html
import re
import sys
from pathlib import Path

repo = Path(sys.argv[1] if len(sys.argv) > 1 else "build/repo")
rows = []
for metadata in sorted(repo.glob("cc/nitea/*/maven-metadata.xml")):
    text = metadata.read_text(encoding="utf-8")
    artifact = re.search(r"<artifactId>([^<]+)</artifactId>", text).group(1)
    versions = re.findall(r"<version>([^<]+)</version>", text.split("<versions>", 1)[-1])
    latest = versions[-1] if versions else ""
    links = ", ".join(
        f'<a href="cc/nitea/{artifact}/{v}/{artifact}-{v}.jar">{html.escape(v)}</a>' for v in reversed(versions)
    )
    rows.append(f"<tr><td><code>cc.nitea:{artifact}:{latest}</code></td><td>{links}</td></tr>")

page = f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Nitea libraries</title>
<style>
  :root {{ color-scheme: dark; }}
  body {{ margin: 0; background: #09090b; color: #e4e4e7; font: 15px/1.6 system-ui, sans-serif; }}
  main {{ max-width: 860px; margin: 0 auto; padding: 48px 16px; }}
  h1 {{ margin: 0 0 8px; font-size: 28px; }}
  a {{ color: #2dd4bf; }}
  code, pre {{ font-family: ui-monospace, Menlo, monospace; font-size: 13px; }}
  pre {{ background: #18181b; border: 1px solid #27272a; border-radius: 8px; padding: 12px 16px; overflow-x: auto; }}
  table {{ width: 100%; border-collapse: collapse; margin-top: 16px; }}
  td, th {{ text-align: left; padding: 8px; border-bottom: 1px solid #27272a; vertical-align: top; }}
  p {{ color: #a1a1aa; }}
</style>
</head>
<body>
<main>
<h1>Nitea libraries</h1>
<p>The Maven repository of the <a href="https://nitea.cc">Nitea</a> library for NeoForge, Forge and Fabric mods.
Pick the artifact for your loader and Minecraft version; the <a href="https://docs.nitea.cc">docs</a> show the
exact setup for each loader.</p>
<pre>repositories {{
    maven {{ url 'https://libraries.nitea.cc' }}
}}</pre>
<table>
<thead><tr><th>Dependency (latest)</th><th>Versions</th></tr></thead>
<tbody>
{chr(10).join(rows)}
</tbody>
</table>
<p>Source: <a href="https://github.com/pyrogenous/NSDK">github.com/pyrogenous/NSDK</a></p>
</main>
</body>
</html>
"""
(repo / "index.html").write_text(page, encoding="utf-8")
print(f"index.html: {len(rows)} artifacts")
