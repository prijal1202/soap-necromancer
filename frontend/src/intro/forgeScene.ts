// The opening scene, filmed with a moving camera: a close-up of the anvil face pulls back to the
// whole anvil in front of the forge fire; a white-hot blade lands on it; the camera pushes in on the
// blade and back out; a hammer beats it three times (flash, sparks, scale, shake); it cools to
// steel. Fire is a WebGL noise shader behind everything; the rest is a
// 2D canvas drawn in world units through the camera. No assets, no dependencies, no eval.

/**
 * Playback speed of the timeline below (camera, drop, strikes, cooling). Particle physics
 * and the flames run in real time, so sparks never look fast-forwarded.
 */
export const SPEED = 1.5;

/** Timeline, in timeline seconds (divide by SPEED for wall-clock seconds). */
export const T = {
  bladeDrop: [1.5, 1.85],
  strikes: [3.2, 3.55, 3.9],
  cool: [4.0, 4.85],
} as const;

/** Camera keys: time, zoom, focus x, focus y (world units). Eased in and out between keys. */
const CAMERA: [number, number, number, number][] = [
  [0.0, 5.4, 70, 28],
  [0.35, 5.0, 70, 25],
  [1.55, 1.0, 0, 110],
  [1.95, 1.0, 0, 110],
  [2.5, 3.1, -20, -20],
  [2.7, 3.1, -20, -20],
  [3.1, 1.35, 10, 40],
  [4.0, 1.35, 10, 40],
  [5.6, 1.6, 30, 20], // a slow push in while the blade cools and the title comes up
];

export interface Scene {
  stop(): void;
}

// ---------------------------------------------------------------- helpers

const HEAT_STOPS: [number, number, number, number][] = [
  [0.0, 0, 0, 0],
  [0.15, 70, 8, 2],
  [0.35, 170, 28, 4],
  [0.55, 235, 80, 12],
  [0.75, 255, 165, 50],
  [0.9, 255, 225, 140],
  [1.0, 255, 250, 228],
];

/** Glow colour of hot steel, h = 0 (cold) .. 1 (white heat). */
function heatRGB(h: number): [number, number, number] {
  const x = clamp01(h);
  for (let i = 1; i < HEAT_STOPS.length; i++) {
    const b = HEAT_STOPS[i]!;
    if (x <= b[0]) {
      const a = HEAT_STOPS[i - 1]!;
      const k = (x - a[0]) / (b[0] - a[0]);
      return [
        a[1] + (b[1] - a[1]) * k,
        a[2] + (b[2] - a[2]) * k,
        a[3] + (b[3] - a[3]) * k,
      ];
    }
  }
  return [255, 250, 228];
}

const rgba = ([r, g, b]: [number, number, number], a: number) =>
  `rgba(${r | 0},${g | 0},${b | 0},${clamp01(a).toFixed(3)})`;

function clamp01(x: number) {
  return Math.min(1, Math.max(0, x));
}
const smooth = (a: number, b: number, x: number) => {
  const k = clamp01((x - a) / (b - a));
  return k * k * (3 - 2 * k);
};
const easeInOut = (k: number) =>
  k < 0.5 ? 4 * k * k * k : 1 - Math.pow(-2 * k + 2, 3) / 2;
const rand = (a: number, b: number) => a + Math.random() * (b - a);

function camera(t: number): { z: number; x: number; y: number } {
  const keys = CAMERA;
  if (t <= keys[0]![0])
    return { z: keys[0]![1], x: keys[0]![2], y: keys[0]![3] };
  for (let i = 1; i < keys.length; i++) {
    const b = keys[i]!;
    if (t <= b[0]) {
      const a = keys[i - 1]!;
      const k = easeInOut((t - a[0]) / (b[0] - a[0]));
      // Zoom is interpolated in log space, so a pull-back feels like a steady dolly.
      const z = Math.exp(
        Math.log(a[1]) + (Math.log(b[1]) - Math.log(a[1])) * k,
      );
      return { z, x: a[2] + (b[2] - a[2]) * k, y: a[3] + (b[3] - a[3]) * k };
    }
  }
  const last = keys[keys.length - 1]!;
  return { z: last[1], x: last[2], y: last[3] };
}

// ---------------------------------------------------------------- fire (WebGL, behind everything)

const VERT = `attribute vec2 a; void main() { gl_Position = vec4(a, 0.0, 1.0); }`;

const FRAG = `
precision highp float;
uniform vec2 uRes;
uniform float uTime;
uniform float uFire;
uniform float uZoom;
uniform vec3 uFlash;

float hash(vec2 p) { p = fract(p * vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }
float noise(vec2 p) {
  vec2 i = floor(p); vec2 f = fract(p); vec2 u = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}
float fbm(vec2 p) {
  float v = 0.0; float a = 0.5; mat2 m = mat2(1.6, 1.2, -1.2, 1.6);
  for (int i = 0; i < 5; i++) { v += a * noise(p); p = m * p; a *= 0.5; }
  return v;
}
vec3 fireColor(float t) {
  vec3 c = mix(vec3(0.0), vec3(0.5, 0.03, 0.0), smoothstep(0.0, 0.25, t));
  c = mix(c, vec3(1.0, 0.3, 0.02), smoothstep(0.2, 0.5, t));
  c = mix(c, vec3(1.0, 0.7, 0.18), smoothstep(0.45, 0.78, t));
  return mix(c, vec3(1.0, 0.96, 0.85), smoothstep(0.8, 1.0, t));
}
void main() {
  vec2 uv0 = gl_FragCoord.xy / uRes;
  // The fire is further away than the anvil, so it zooms less (parallax).
  vec2 uv = (uv0 - vec2(0.5, 0.35)) / uZoom + vec2(0.5, 0.35);
  float aspect = uRes.x / uRes.y;
  vec2 p = vec2((uv.x - 0.5) * aspect, uv.y);
  float t = uTime;

  vec2 q = vec2(p.x * 2.2, p.y * 1.6 - t * 1.2);
  vec2 warp = vec2(fbm(q + vec2(0.0, t * 0.35)), fbm(q + vec2(5.2, 1.3 - t * 0.25)));
  float n = fbm(q * 1.25 + warp * 1.7 + vec2(0.0, -t * 0.9));

  float reach = 0.1 + 0.75 * uFire;
  float flame = n * 1.6 - (uv.y / reach) * 1.0 - 0.12;
  flame += (1.0 - smoothstep(0.0, 0.08, uv.y)) * 0.5 * uFire;
  flame = clamp(flame * 1.8, 0.0, 1.0);
  vec3 col = fireColor(flame) * smoothstep(0.0, 0.15, flame);

  // Firelight in the smoke, and the dark workshop behind it.
  col += vec3(0.24, 0.055, 0.01) * uFire * pow(clamp(1.0 - uv.y * 0.9, 0.0, 1.0), 2.0) * 0.6;
  vec2 d = (uv0 - uFlash.xy) * vec2(aspect, 1.0);
  col += vec3(1.0, 0.8, 0.5) * uFlash.z * 0.35 * exp(-dot(d, d) * 3.0);
  col += (hash(gl_FragCoord.xy + fract(t) * 91.0) - 0.5) * 0.03;
  gl_FragColor = vec4(max(col, 0.0), 1.0);
}`;

interface Fire {
  draw(
    time: number,
    fire: number,
    zoom: number,
    flash: [number, number, number],
  ): void;
  resize(w: number, h: number): void;
  dispose(): void;
}

function createFire(canvas: HTMLCanvasElement): Fire | null {
  const gl = canvas.getContext("webgl", { antialias: false, alpha: false });
  if (!gl) return null;
  const compile = (type: number, src: string) => {
    const s = gl.createShader(type)!;
    gl.shaderSource(s, src);
    gl.compileShader(s);
    return gl.getShaderParameter(s, gl.COMPILE_STATUS) ? s : null;
  };
  const vs = compile(gl.VERTEX_SHADER, VERT);
  const fs = compile(gl.FRAGMENT_SHADER, FRAG);
  if (!vs || !fs) return null;
  const prog = gl.createProgram()!;
  gl.attachShader(prog, vs);
  gl.attachShader(prog, fs);
  gl.linkProgram(prog);
  if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) return null;
  gl.useProgram(prog);
  gl.bindBuffer(gl.ARRAY_BUFFER, gl.createBuffer());
  gl.bufferData(
    gl.ARRAY_BUFFER,
    new Float32Array([-1, -1, 3, -1, -1, 3]),
    gl.STATIC_DRAW,
  );
  const loc = gl.getAttribLocation(prog, "a");
  gl.enableVertexAttribArray(loc);
  gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);
  const u = (name: string) => gl.getUniformLocation(prog, name);
  const [uRes, uTime, uFire, uZoom, uFlash] = [
    "uRes",
    "uTime",
    "uFire",
    "uZoom",
    "uFlash",
  ].map(u);
  return {
    resize(w, h) {
      canvas.width = Math.max(1, Math.round(w * 0.5));
      canvas.height = Math.max(1, Math.round(h * 0.5));
      gl.viewport(0, 0, canvas.width, canvas.height);
    },
    draw(time, fire, zoom, flash) {
      gl.uniform2f(uRes!, canvas.width, canvas.height);
      gl.uniform1f(uTime!, time);
      gl.uniform1f(uFire!, fire);
      gl.uniform1f(uZoom!, zoom);
      gl.uniform3f(uFlash!, flash[0], flash[1], flash[2]);
      gl.drawArrays(gl.TRIANGLES, 0, 3);
    },
    dispose() {
      gl.getExtension("WEBGL_lose_context")?.loseContext();
    },
  };
}

// ---------------------------------------------------------------- textures (generated once)

function canvas2d(
  w: number,
  h: number,
): [HTMLCanvasElement, CanvasRenderingContext2D] {
  const c = document.createElement("canvas");
  c.width = w;
  c.height = h;
  return [c, c.getContext("2d")!];
}

/** Hairlines along one direction: ground steel. */
function brushed(
  w: number,
  h: number,
  lines: number,
  angle = 0,
): HTMLCanvasElement {
  const [c, g] = canvas2d(w, h);
  g.fillStyle = "rgb(128,128,128)";
  g.fillRect(0, 0, w, h);
  for (let i = 0; i < lines; i++) {
    const light = Math.random() < 0.5;
    g.strokeStyle = light
      ? `rgba(255,255,255,${rand(0.04, 0.2)})`
      : `rgba(0,0,0,${rand(0.05, 0.22)})`;
    g.lineWidth = rand(0.5, 1.5);
    const x = Math.random() * w;
    const y = Math.random() * h;
    const len = rand(20, w * 0.5);
    const a = angle + rand(-0.04, 0.04);
    g.beginPath();
    g.moveTo(x, y);
    g.lineTo(x + Math.cos(a) * len, y + Math.sin(a) * len);
    g.stroke();
  }
  return c;
}

/** Pitted, mottled noise: cast iron, stone, or film grain. */
function mottled(
  size: number,
  blotches: number,
  grain: number,
): HTMLCanvasElement {
  const [c, g] = canvas2d(size, size);
  g.fillStyle = "rgb(128,128,128)";
  g.fillRect(0, 0, size, size);
  for (let i = 0; i < blotches; i++) {
    const r = rand(2, size * 0.06);
    const x = Math.random() * size;
    const y = Math.random() * size;
    const grad = g.createRadialGradient(x, y, 0, x, y, r);
    const v = Math.random() < 0.5 ? 0 : 255;
    grad.addColorStop(0, `rgba(${v},${v},${v},${rand(0.05, 0.18)})`);
    grad.addColorStop(1, `rgba(${v},${v},${v},0)`);
    g.fillStyle = grad;
    g.fillRect(x - r, y - r, r * 2, r * 2);
  }
  const img = g.getImageData(0, 0, size, size);
  for (let i = 0; i < img.data.length; i += 4) {
    const n = (Math.random() - 0.5) * grain;
    img.data[i] = clampByte(img.data[i]! + n);
    img.data[i + 1] = clampByte(img.data[i + 1]! + n);
    img.data[i + 2] = clampByte(img.data[i + 2]! + n);
  }
  g.putImageData(img, 0, 0);
  return c;
}

function clampByte(v: number) {
  return v < 0 ? 0 : v > 255 ? 255 : v;
}

// ---------------------------------------------------------------- the anvil (world units, face at y = 0)

function anvilBody(ctx: CanvasRenderingContext2D) {
  ctx.beginPath();
  ctx.moveTo(-500, 14);
  ctx.quadraticCurveTo(-330, 4, -170, 0);
  ctx.lineTo(400, 0);
  ctx.lineTo(408, 8);
  ctx.lineTo(408, 92);
  ctx.quadraticCurveTo(330, 110, 235, 150);
  ctx.quadraticCurveTo(198, 200, 200, 255);
  ctx.quadraticCurveTo(215, 302, 330, 318);
  ctx.lineTo(348, 352);
  ctx.lineTo(-300, 352);
  ctx.lineTo(-284, 318);
  ctx.quadraticCurveTo(-148, 302, -125, 255);
  ctx.quadraticCurveTo(-118, 190, -160, 150);
  ctx.quadraticCurveTo(-300, 112, -500, 24);
  ctx.closePath();
}

/** The ground top surface, seen slightly from above. */
function anvilTop(ctx: CanvasRenderingContext2D) {
  ctx.beginPath();
  ctx.moveTo(-500, 14);
  ctx.quadraticCurveTo(-330, 4, -170, 0);
  ctx.lineTo(400, 0);
  ctx.lineTo(420, -30);
  ctx.lineTo(-150, -30);
  ctx.quadraticCurveTo(-330, -22, -497, 8);
  ctx.closePath();
}

// ---------------------------------------------------------------- the blade (world units)

interface Blade {
  x0: number;
  x1: number;
  tip: number;
  y: number;
  h: number;
}

function bladePath(ctx: CanvasRenderingContext2D, b: Blade) {
  const top = b.y - b.h / 2;
  const bottom = b.y + b.h / 2;
  ctx.beginPath();
  ctx.moveTo(b.x1, top);
  ctx.lineTo(b.tip - b.h * 2.2, top);
  ctx.quadraticCurveTo(
    b.tip - b.h * 0.6,
    top + b.h * 0.05,
    b.tip,
    b.y - b.h * 0.12,
  );
  ctx.quadraticCurveTo(
    b.tip - b.h * 0.9,
    bottom - b.h * 0.05,
    b.tip - b.h * 4,
    bottom,
  );
  ctx.lineTo(b.x1, bottom);
  ctx.closePath();
}

function tangPath(ctx: CanvasRenderingContext2D, b: Blade) {
  const th = b.h * 0.42;
  ctx.beginPath();
  ctx.moveTo(b.x1 + 1, b.y - b.h / 2);
  ctx.lineTo(b.x1 + 1, b.y + b.h / 2);
  ctx.quadraticCurveTo(
    b.x1 - b.h * 0.3,
    b.y + th / 2,
    b.x1 - b.h * 1.1,
    b.y + th / 2,
  );
  ctx.lineTo(b.x0 + b.h * 0.3, b.y + th / 2 - b.h * 0.04);
  ctx.lineTo(b.x0, b.y);
  ctx.lineTo(b.x0 + b.h * 0.3, b.y - th / 2 + b.h * 0.04);
  ctx.lineTo(b.x1 - b.h * 1.1, b.y - th / 2);
  ctx.quadraticCurveTo(b.x1 - b.h * 0.3, b.y - th / 2, b.x1 + 1, b.y - b.h / 2);
  ctx.closePath();
}

// ---------------------------------------------------------------- particles

interface Particle {
  x: number;
  y: number;
  vx: number;
  vy: number;
  age: number;
  life: number;
  size: number;
  kind: "spark" | "ember" | "scale";
  drag: number;
  gravity: number;
  heat: number;
}

const MAX_PARTICLES = 2400;

/** Where along the blade (0 = shoulder, 1 = tip) strike i lands. */
const strikeU = (i: number) => 0.42 + i * 0.12;

// ---------------------------------------------------------------- the hammer

const HEAD_LEN = 150;
const HEAD_W = 92;
const HANDLE = 560;
const CONTACT_ANGLE = 0.12;

/** Hammer swing offsets (radians above the contact angle); downswings accelerate like a real blow. */
function hammerAngle(t: number): number | null {
  const s = T.strikes;
  const first = s[0] - 0.65;
  const last = s[s.length - 1]! + 0.7;
  if (t < first || t > last) return null;
  const keys: [number, number, "smooth" | "in" | "out"][] = [
    [first, 1.7, "smooth"],
  ];
  for (const ts of s) {
    keys.push(
      [ts - 0.16, 0.95, "smooth"],
      [ts, 0, "in"],
      [ts + 0.2, 0.6, "out"],
    );
  }
  keys.push([last, 1.7, "smooth"]);
  for (let i = 1; i < keys.length; i++) {
    const b = keys[i]!;
    if (t <= b[0]) {
      const a = keys[i - 1]!;
      const k = clamp01((t - a[0]) / (b[0] - a[0]));
      const e =
        b[2] === "in"
          ? k * k
          : b[2] === "out"
            ? 1 - (1 - k) * (1 - k)
            : easeInOut(k);
      return a[1] + (b[1] - a[1]) * e;
    }
  }
  return null;
}

// ---------------------------------------------------------------- scene

export function startScene(
  fireCanvas: HTMLCanvasElement,
  fxCanvas: HTMLCanvasElement,
  stage: HTMLElement,
): Scene {
  const ctx = fxCanvas.getContext("2d")!;
  const fire = createFire(fireCanvas);
  if (!fire) fireCanvas.style.display = "none";

  const steelGrain = brushed(1024, 96, 900);
  const faceScratches = brushed(1024, 128, 1400, 0.02);
  const castIron = mottled(512, 900, 40);
  const filmGrain = mottled(256, 0, 60);
  const grainPattern = ctx.createPattern(filmGrain, "repeat");
  const woodGrain = brushed(512, 64, 260);

  const particles: Particle[] = [];
  const hotspots: { u: number; age: number }[] = [];
  const hammerTrail: number[] = [];
  let W = 0;
  let H = 0;
  let dpr = 1;
  let base = 1;

  function resize() {
    W = stage.clientWidth;
    H = stage.clientHeight;
    dpr = Math.min(window.devicePixelRatio || 1, 2);
    fxCanvas.width = Math.round(W * dpr);
    fxCanvas.height = Math.round(H * dpr);
    // At zoom 1 the anvil and the room above it for the hammer fit the screen.
    base = Math.min((W * 0.8) / 940, (H * 0.62) / 520);
    fire?.resize(W, H);
  }
  resize();
  window.addEventListener("resize", resize);

  let flash = 0;
  let shake = 0;
  let jolt = 0;
  let struck = 0;
  let landed = false;
  let emberClock = 0;
  const start = performance.now();
  let last = start;
  let raf = 0;

  const push = (p: Particle) => {
    if (particles.length < MAX_PARTICLES) particles.push(p);
  };

  function blade(t: number): Blade {
    const L = 780;
    const h = 34;
    const k = clamp01((t - T.bladeDrop[0]) / (T.bladeDrop[1] - T.bladeDrop[0]));
    const rest = -19;
    const y = rest - 1100 * (1 - k * k) + jolt; // falls with gravity
    const x0 = -430;
    return { x0, x1: x0 + L * 0.2, tip: x0 + L, y, h };
  }

  function heatAt(t: number) {
    return Math.pow(
      1 - clamp01((t - T.cool[0]) / (T.cool[1] - T.cool[0])),
      1.6,
    );
  }

  function burst(x: number, y: number, count: number, speed: number) {
    for (let i = 0; i < count; i++) {
      const a = rand(-Math.PI * 0.97, -Math.PI * 0.03);
      const s = rand(0.25, 1) * speed * (Math.random() < 0.15 ? 1.4 : 1);
      push({
        x: x + rand(-25, 25),
        y,
        vx: Math.cos(a) * s,
        vy: Math.sin(a) * s,
        age: 0,
        life: rand(0.35, 1.1),
        size: rand(0.9, 2.3),
        kind: "spark",
        drag: 1.1,
        gravity: 1300,
        heat: 1,
      });
    }
  }

  function strike(b: Blade) {
    const u = strikeU(struck);
    const x = b.x1 + (b.tip - b.x1) * u;
    const y = b.y - b.h / 2;
    struck++;
    flash = 1;
    shake = 1;
    jolt = 4;
    hotspots.push({ u, age: 0 });
    burst(x, y, 120, 1150);
    for (let i = 0; i < 20; i++) {
      const a = rand(-Math.PI * 0.9, -Math.PI * 0.1);
      const s = rand(120, 420);
      push({
        x: x + rand(-40, 40),
        y,
        vx: Math.cos(a) * s,
        vy: Math.sin(a) * s,
        age: 0,
        life: rand(0.7, 1.3),
        size: rand(2, 4.5),
        kind: "scale",
        drag: 2.2,
        gravity: 1500,
        heat: 0.7,
      });
    }
  }

  function frame(now: number) {
    const dt = Math.min(0.05, (now - last) / 1000);
    last = now;
    const real = (now - start) / 1000;
    const t = real * SPEED;
    const cam = camera(t);

    // ---- events and physics
    const b = blade(t);
    if (!landed && t >= T.bladeDrop[1]) {
      landed = true;
      shake = 0.55;
      jolt = 6;
      burst(b.x1 + (b.tip - b.x1) * 0.5, b.y + b.h / 2, 40, 600);
    }
    while (struck < T.strikes.length && t >= T.strikes[struck]!)
      strike(blade(t));
    flash *= Math.exp(-dt * 13);
    shake *= Math.exp(-dt * 11);
    jolt *= Math.exp(-dt * 16);
    for (const s of hotspots) s.age += dt;

    const heat = heatAt(t);
    const fireLevel =
      smooth(0, 0.8, t) * (1 - 0.45 * smooth(T.cool[0], T.cool[1], t));

    emberClock += dt * 30 * fireLevel;
    while (emberClock > 1) {
      emberClock--;
      push({
        x: rand(-1100, 1100),
        y: 520,
        vx: rand(-25, 25),
        vy: rand(-200, -80),
        age: 0,
        life: rand(2, 4),
        size: rand(0.8, 2),
        kind: "ember",
        drag: 0.2,
        gravity: -20,
        heat: rand(0.5, 0.85),
      });
    }

    for (let i = particles.length - 1; i >= 0; i--) {
      const p = particles[i]!;
      p.age += dt;
      if (p.age > p.life) {
        particles[i] = particles[particles.length - 1]!;
        particles.pop();
        continue;
      }
      const d = Math.exp(-p.drag * dt);
      p.vx *= d;
      p.vy = p.vy * d + p.gravity * dt;
      if (p.kind === "ember") p.vx += Math.sin((p.age + p.x) * 3) * 12 * dt;
      p.x += p.vx * dt;
      p.y += p.vy * dt;
      // Sparks and scale that reach the anvil face skip off it.
      if (
        p.kind !== "ember" &&
        p.vy > 0 &&
        p.y > -30 &&
        p.y < 0 &&
        p.x > -170 &&
        p.x < 410
      ) {
        p.vy *= -0.35;
        p.vx *= 0.6;
        p.y = -30;
      }
    }

    // ---- draw, through the camera
    const s = base * cam.z;
    const sx = (Math.random() - 0.5) * 18 * shake;
    const sy = (Math.random() - 0.5) * 12 * shake;
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, fxCanvas.width, fxCanvas.height);
    ctx.setTransform(
      dpr * s,
      0,
      0,
      dpr * s,
      dpr * (W / 2 - cam.x * s + sx),
      dpr * (H * 0.56 - cam.y * s + sy),
    );

    drawAnvil(fireLevel, b, heat, s);
    drawBlade(b, heat, t, s);
    drawHammer(t, b, fireLevel, heat);
    drawParticles(s);

    // Film grain over the whole frame, in screen space.
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    if (grainPattern) {
      ctx.save();
      ctx.globalCompositeOperation = "overlay";
      ctx.globalAlpha = 0.09;
      ctx.translate(Math.random() * 256, Math.random() * 256);
      ctx.fillStyle = grainPattern;
      ctx.fillRect(-256, -256, fxCanvas.width + 512, fxCanvas.height + 512);
      ctx.restore();
    }

    const flashScreenX =
      (W / 2 + (b.x1 + (b.tip - b.x1) * 0.55 - cam.x) * s) / W;
    const flashScreenY = 1 - (H * 0.56 + (b.y - cam.y) * s) / H;
    fire?.draw(real, fireLevel, 1 + (cam.z - 1) * 0.3, [
      flashScreenX,
      flashScreenY,
      flash,
    ]);
    raf = requestAnimationFrame(frame);
  }

  function drawAnvil(fireLevel: number, b: Blade, heat: number, s: number) {
    // Body: dark cast iron, mottled, lit warm from the forge behind and below.
    ctx.save();
    anvilBody(ctx);
    ctx.clip();
    const body = ctx.createLinearGradient(0, 0, 0, 352);
    body.addColorStop(0, "#34383d");
    body.addColorStop(0.12, "#22252a");
    body.addColorStop(0.6, "#141518");
    body.addColorStop(1, "#0b0b0c");
    ctx.fillStyle = body;
    ctx.fillRect(-520, -40, 960, 420);
    ctx.globalCompositeOperation = "overlay";
    ctx.globalAlpha = 0.9;
    ctx.drawImage(castIron, -520, -40, 960, 420);
    ctx.globalAlpha = 1;
    // The hardened face plate welded on top, and its seam.
    ctx.globalCompositeOperation = "source-over";
    const plate = ctx.createLinearGradient(0, 0, 0, 44);
    plate.addColorStop(0, "rgba(120,128,138,0.55)");
    plate.addColorStop(1, "rgba(60,66,72,0.25)");
    ctx.fillStyle = plate;
    ctx.fillRect(-175, 0, 590, 44);
    ctx.strokeStyle = "rgba(0,0,0,0.6)";
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.moveTo(-170, 44);
    ctx.lineTo(408, 44);
    ctx.stroke();
    // Firelight: warm from the left and below, a rim along the silhouette.
    ctx.globalCompositeOperation = "lighter";
    const warm = ctx.createLinearGradient(-520, 0, 420, 0);
    warm.addColorStop(0, `rgba(255,110,30,${(0.22 * fireLevel).toFixed(3)})`);
    warm.addColorStop(0.5, "rgba(255,110,30,0)");
    warm.addColorStop(1, `rgba(255,90,20,${(0.14 * fireLevel).toFixed(3)})`);
    ctx.fillStyle = warm;
    ctx.fillRect(-520, -40, 960, 420);
    ctx.restore();

    ctx.save();
    ctx.globalCompositeOperation = "lighter";
    ctx.strokeStyle = `rgba(255,120,40,${(0.35 * fireLevel).toFixed(3)})`;
    ctx.lineWidth = 3;
    ctx.shadowColor = `rgba(255,100,20,${(0.8 * fireLevel).toFixed(3)})`;
    ctx.shadowBlur = Math.min(18 * s * dpr, 80);
    anvilBody(ctx);
    ctx.stroke();
    ctx.restore();

    // Top: ground bright, scratched by years of work, reflecting the fire.
    ctx.save();
    anvilTop(ctx);
    ctx.clip();
    const top = ctx.createLinearGradient(0, -30, 0, 0);
    top.addColorStop(0, "#6d757e");
    top.addColorStop(0.5, "#aab3bc");
    top.addColorStop(1, "#d9dfe5");
    ctx.fillStyle = top;
    ctx.fillRect(-510, -34, 940, 50);
    ctx.globalCompositeOperation = "overlay";
    ctx.drawImage(faceScratches, -510, -34, 940, 50);
    ctx.globalCompositeOperation = "multiply";
    ctx.globalAlpha = 0.5;
    ctx.drawImage(castIron, -510, -34, 940, 50);
    ctx.globalAlpha = 1;
    // The hardy hole and the pritchel hole near the heel.
    ctx.globalCompositeOperation = "source-over";
    ctx.fillStyle = "#060607";
    ctx.beginPath();
    ctx.moveTo(318, -22);
    ctx.lineTo(352, -22);
    ctx.lineTo(346, -8);
    ctx.lineTo(312, -8);
    ctx.closePath();
    ctx.fill();
    ctx.beginPath();
    ctx.ellipse(270, -15, 8, 4, 0, 0, Math.PI * 2);
    ctx.fill();
    ctx.globalCompositeOperation = "lighter";
    ctx.fillStyle = `rgba(255,130,50,${(0.16 * fireLevel).toFixed(3)})`;
    ctx.fillRect(-510, -34, 940, 50);
    // Light thrown on the face by the hot blade lying on it.
    if (heat > 0.02 && b.y > -200) {
      const cx = (b.x1 + b.tip) / 2;
      const glow = ctx.createRadialGradient(cx, -10, 0, cx, -10, 420);
      glow.addColorStop(0, rgba(heatRGB(0.85 * heat), 0.55 * heat));
      glow.addColorStop(1, rgba(heatRGB(0.6 * heat), 0));
      ctx.fillStyle = glow;
      ctx.fillRect(-510, -34, 940, 50);
    }
    ctx.restore();

    // The face's leading edge catches a thin line of light.
    ctx.save();
    ctx.strokeStyle = "rgba(235,240,245,0.55)";
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    ctx.moveTo(-170, 0.5);
    ctx.lineTo(400, 0.5);
    ctx.stroke();
    ctx.restore();
  }

  function drawBlade(b: Blade, heat: number, t: number, s: number) {
    if (b.y < -1400) return;
    const top = b.y - b.h / 2;
    const steel = ctx.createLinearGradient(0, top, 0, top + b.h);
    steel.addColorStop(0, "#16181b");
    steel.addColorStop(0.08, "#3e444b");
    steel.addColorStop(0.42, "#737b84");
    steel.addColorStop(0.55, "#2b3035");
    steel.addColorStop(0.6, "#a8b1ba");
    steel.addColorStop(0.9, "#d6dde4");
    steel.addColorStop(1, "#f1f4f7");

    // A soft contact shadow where the blade lies on the face.
    if (t > T.bladeDrop[1] - 0.05) {
      ctx.save();
      ctx.fillStyle = "rgba(0,0,0,0.35)";
      ctx.filter = `blur(${(3 * s).toFixed(1)}px)`;
      ctx.fillRect(b.x1, b.y + b.h / 2 - 2, b.tip - b.x1 - 30, 6);
      ctx.restore();
    }

    for (const part of ["tang", "blade"] as const) {
      ctx.save();
      if (part === "tang") tangPath(ctx, b);
      else bladePath(ctx, b);
      ctx.clip();
      ctx.fillStyle = part === "tang" ? "#2e3236" : steel;
      ctx.fillRect(b.x0 - 2, top - 2, b.tip - b.x0 + 4, b.h + 4);
      ctx.globalCompositeOperation = "overlay";
      ctx.globalAlpha = 0.55;
      ctx.drawImage(steelGrain, b.x0, top, b.tip - b.x0, b.h);
      ctx.globalAlpha = 1;
      ctx.globalCompositeOperation = "source-over";

      if (part === "blade") {
        const fx0 = b.x1 + b.h * 1.2;
        const fx1 = b.x1 + (b.tip - b.x1) * 0.68;
        const fy = top + b.h * 0.3;
        ctx.lineCap = "round";
        ctx.strokeStyle = "rgba(8,9,11,0.75)";
        ctx.lineWidth = b.h * 0.1;
        ctx.beginPath();
        ctx.moveTo(fx0, fy);
        ctx.lineTo(fx1, fy);
        ctx.stroke();
        ctx.strokeStyle = "rgba(220,228,236,0.35)";
        ctx.lineWidth = b.h * 0.035;
        ctx.beginPath();
        ctx.moveTo(fx0, fy + b.h * 0.06);
        ctx.lineTo(fx1, fy + b.h * 0.06);
        ctx.stroke();
      }

      if (heat > 0.002) {
        const grad = ctx.createLinearGradient(b.x0, 0, b.tip, 0);
        for (let i = 0; i <= 16; i++) {
          const u = i / 16;
          const local = heatProfile(u, heat);
          grad.addColorStop(u, rgba(heatRGB(local), smooth(0.04, 0.5, local)));
        }
        ctx.fillStyle = grad;
        ctx.fillRect(b.x0 - 2, top - 2, b.tip - b.x0 + 4, b.h + 4);
        ctx.globalAlpha = 0.35 * smooth(0.3, 0.9, heat);
        ctx.globalCompositeOperation = "multiply";
        ctx.drawImage(castIron, b.x0, top, b.tip - b.x0, b.h);
        ctx.globalCompositeOperation = "source-over";
        ctx.globalAlpha = 1;
      }
      ctx.restore();
    }

    if (heat > 0.02) {
      ctx.save();
      ctx.globalCompositeOperation = "lighter";
      ctx.shadowColor = rgba(heatRGB(heat * 0.85), 0.85 * heat);
      ctx.shadowBlur = Math.min(50 * s * dpr, 140); // large blurs are slow; this is plenty in a close-up
      ctx.fillStyle = rgba(heatRGB(heat * 0.9), 0.22 * heat);
      bladePath(ctx, b);
      ctx.fill();
      ctx.restore();
    }
  }

  function heatProfile(u: number, heat: number): number {
    let h = heat * (0.78 + 0.22 * Math.exp(-Math.pow((u - 0.55) / 0.3, 2)));
    for (const s of hotspots)
      h +=
        0.25 *
        heat *
        Math.exp(-s.age * 2.2) *
        Math.exp(-Math.pow((u - 0.2 - 0.8 * s.u) / 0.07, 2));
    h *= 1 - 0.35 * (1 - heat) * u; // the thin tip cools first
    return clamp01(h);
  }

  function drawHammer(t: number, b: Blade, fireLevel: number, heat: number) {
    const offset = hammerAngle(t);
    if (offset === null) {
      hammerTrail.length = 0;
      return;
    }
    // Aim at the next blow's spot (each lands a little further along), and keep it after the last.
    // The pivot is placed so the hammer's face meets the top of the blade at the contact angle.
    const next = T.strikes.findIndex((ts) => t < ts + 0.2);
    const u = strikeU(next < 0 ? T.strikes.length - 1 : next);
    const cx = b.x1 + (b.tip - b.x1) * u;
    const cy = b.y - b.h / 2;
    const fxl = -HANDLE;
    const fyl = HEAD_LEN / 2;
    const ca = Math.cos(CONTACT_ANGLE);
    const sa = Math.sin(CONTACT_ANGLE);
    const px = cx - (fxl * ca - fyl * sa);
    const py = cy - (fxl * sa + fyl * ca);

    hammerTrail.unshift(offset);
    if (hammerTrail.length > 4) hammerTrail.length = 4;
    // Motion blur: fainter copies along the last few frames of the swing.
    for (let i = hammerTrail.length - 1; i >= 0; i--) {
      const ghost = i > 0;
      if (ghost && Math.abs(hammerTrail[i]! - offset) < 0.03) continue;
      ctx.save();
      ctx.globalAlpha = ghost ? 0.18 / i : 1;
      ctx.translate(px, py);
      ctx.rotate(CONTACT_ANGLE + hammerTrail[i]!);
      drawHammerShape(fireLevel, ghost ? 0 : heat * smooth(0.5, 0, offset));
      ctx.restore();
    }
  }

  function drawHammerShape(fireLevel: number, glowFromBlade: number) {
    // Handle: ash wood, tapering toward the head, running off-screen to the smith's hand.
    ctx.save();
    ctx.beginPath();
    ctx.moveTo(-HANDLE + HEAD_W * 0.3, -14);
    ctx.lineTo(500, -22);
    ctx.lineTo(500, 22);
    ctx.lineTo(-HANDLE + HEAD_W * 0.3, 14);
    ctx.closePath();
    ctx.clip();
    const wood = ctx.createLinearGradient(0, -22, 0, 22);
    wood.addColorStop(0, "#2a180c");
    wood.addColorStop(0.35, "#7a5030");
    wood.addColorStop(0.6, "#5b3a21");
    wood.addColorStop(1, "#1d1007");
    ctx.fillStyle = wood;
    ctx.fillRect(-HANDLE, -24, HANDLE + 520, 48);
    ctx.globalCompositeOperation = "overlay";
    ctx.drawImage(woodGrain, -HANDLE, -24, HANDLE + 520, 48);
    ctx.restore();

    // Head: forged steel block with chamfers, polished striking face at the bottom.
    const x0 = -HANDLE - HEAD_W / 2;
    const y0 = -HEAD_LEN / 2;
    const c = 10;
    ctx.save();
    ctx.beginPath();
    ctx.moveTo(x0 + c, y0);
    ctx.lineTo(x0 + HEAD_W - c, y0);
    ctx.lineTo(x0 + HEAD_W, y0 + c);
    ctx.lineTo(x0 + HEAD_W, -y0 - c);
    ctx.lineTo(x0 + HEAD_W - c, -y0);
    ctx.lineTo(x0 + c, -y0);
    ctx.lineTo(x0, -y0 - c);
    ctx.lineTo(x0, y0 + c);
    ctx.closePath();
    ctx.clip();
    const head = ctx.createLinearGradient(x0, 0, x0 + HEAD_W, 0);
    head.addColorStop(0, "#15171a");
    head.addColorStop(0.3, "#555c64");
    head.addColorStop(0.55, "#7c848d");
    head.addColorStop(0.8, "#2f3338");
    head.addColorStop(1, "#101113");
    ctx.fillStyle = head;
    ctx.fillRect(x0, y0, HEAD_W, HEAD_LEN);
    ctx.globalCompositeOperation = "overlay";
    ctx.drawImage(castIron, x0, y0, HEAD_W, HEAD_LEN);
    ctx.globalCompositeOperation = "source-over";
    const face = ctx.createLinearGradient(0, -y0 - 14, 0, -y0);
    face.addColorStop(0, "rgba(200,208,216,0)");
    face.addColorStop(1, "rgba(225,231,237,0.85)");
    ctx.fillStyle = face;
    ctx.fillRect(x0, -y0 - 14, HEAD_W, 14);
    ctx.fillStyle = "rgba(190,198,206,0.5)";
    ctx.fillRect(x0, y0, HEAD_W, 6);
    ctx.globalCompositeOperation = "lighter";
    const rim = ctx.createLinearGradient(x0, 0, x0 + HEAD_W * 0.4, 0);
    rim.addColorStop(0, `rgba(255,120,40,${(0.4 * fireLevel).toFixed(3)})`);
    rim.addColorStop(1, "rgba(255,120,40,0)");
    ctx.fillStyle = rim;
    ctx.fillRect(x0, y0, HEAD_W, HEAD_LEN);
    if (glowFromBlade > 0.01) {
      const g = ctx.createLinearGradient(0, -y0, 0, -y0 - 60);
      g.addColorStop(0, rgba(heatRGB(0.85), 0.6 * glowFromBlade));
      g.addColorStop(1, rgba(heatRGB(0.6), 0));
      ctx.fillStyle = g;
      ctx.fillRect(x0, y0, HEAD_W, HEAD_LEN);
    }
    ctx.restore();
  }

  /** Sparks as motion-blurred streaks, batched by colour so a few thousand stay cheap. */
  function drawParticles(s: number) {
    ctx.save();
    ctx.lineCap = "round";
    const buckets = new Map<string, Particle[]>();
    for (const p of particles) {
      if (p.kind === "scale") continue;
      const cool = 1 - p.age / p.life;
      const level = Math.round(clamp01(p.heat * (0.35 + 0.65 * cool)) * 10);
      const alpha = Math.round(clamp01(cool * 1.6) * 4);
      if (alpha === 0) continue;
      const key = `${level}:${alpha}:${p.size > 1.5 ? 1 : 0}`;
      const list = buckets.get(key);
      if (list) list.push(p);
      else buckets.set(key, [p]);
    }
    ctx.globalCompositeOperation = "lighter";
    for (const [key, list] of buckets) {
      const [level, alpha, big] = key.split(":").map(Number) as [
        number,
        number,
        number,
      ];
      ctx.strokeStyle = rgba(heatRGB(level / 10), alpha / 4);
      // Constant on screen whatever the zoom: a spark is a point of light, not an object.
      ctx.lineWidth = (big ? 2.1 : 1.3) / s;
      ctx.beginPath();
      for (const p of list) {
        const streak = p.kind === "ember" ? 0.03 : 0.022;
        ctx.moveTo(p.x, p.y);
        ctx.lineTo(p.x - p.vx * streak, p.y - p.vy * streak);
      }
      ctx.stroke();
    }
    ctx.globalCompositeOperation = "source-over";
    for (const p of particles) {
      if (p.kind !== "scale") continue;
      const cool = 1 - p.age / p.life;
      const [r, g, b] = heatRGB(0.4 * cool);
      ctx.fillStyle = `rgba(${(30 + r * 0.7) | 0},${(16 + g * 0.5) | 0},${(12 + b * 0.4) | 0},${clamp01(cool * 2).toFixed(2)})`;
      ctx.fillRect(p.x - p.size / 2, p.y - p.size / 2, p.size, p.size * 0.6);
    }
    ctx.restore();
  }

  raf = requestAnimationFrame(frame);
  return {
    stop() {
      cancelAnimationFrame(raf);
      window.removeEventListener("resize", resize);
      fire?.dispose();
    },
  };
}
