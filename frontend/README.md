# frontend

The generator's one-page UI: React 19, Vite, TypeScript. It calls the generator API on the same
origin (`POST /api/generate`, `GET /api/sample-wsdl`), unpacks the returned zip in the browser,
and shows the warnings and every generated file in tabs.

You normally don't build this by hand. `mvn package` in the `web` module downloads the pinned Node
(`node.version` in the root `pom.xml`) into `frontend/node/`, runs `npm ci` and `npm run build`, and
copies `dist/` into the jar's `static/` folder.

## Develop

Run the generator on port 8080, then start Vite's dev server, which proxies `/api` and `/mock-bank`
to it:

```
java -jar web/target/soap-necromancer.jar
cd frontend
npm ci
npm run dev
```

## Files

| Path               | What it does                                                                      |
| ------------------ | --------------------------------------------------------------------------------- |
| `src/api.ts`       | API calls, RFC 7807 problem parsing, `Retry-After`                                |
| `src/artifacts.ts` | Unzips the result (fflate), groups files into tabs, reads `generator-report.json` |
| `src/App.tsx`      | Page layout and the idle / working / done / failed states; retries a 503 twice    |
| `src/components/`  | Upload panel, warnings panel, artifact tabs with a plain-text viewer              |

Add dependencies with `npm install <pkg>`. `.npmrc` pins the exact version (no open ranges).
