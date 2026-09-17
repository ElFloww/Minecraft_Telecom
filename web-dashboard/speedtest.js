const PHASES = ['PING', 'DOWNLOAD', 'UPLOAD'];
const TERMINAL = ['FINISHED', 'FAILED', 'REJECTED'];
export const SPEEDTEST_STALE_MS = 6000;
const finite = value => Number.isFinite(value) && value >= 0;

// Presentation only: the engine owns the demand profile, samples and averages.
export class SpeedtestStore {
    constructor() {
        this.generation = 0;
        this.devices = new Map();
    }

    resetWorld(generation) {
        this.generation = generation;
        this.devices.clear();
    }

    get(id) {
        const entry = this.devices.get(id);
        if (entry) {
            this.devices.delete(id);
            this.devices.set(id, entry);
        }
        return entry;
    }

    capture(node, generation, now, reducedMotion = false) {
        const test = node.speedtest;
        if (generation !== this.generation || node.type !== 'ROUTER' || typeof node.id !== 'string'
            || test?.deviceId !== `router:${node.id}` || typeof test.sessionId !== 'string' || !test.sessionId
            || ![...PHASES, ...TERMINAL].includes(test.state)
            || !Number.isInteger(test.ticksElapsed) || test.ticksElapsed < 0
            || !Number.isInteger(test.totalTicksPerPhase) || test.totalTicksPerPhase <= 0) return false;
        let entry = this.get(node.id);
        if (entry?.snapshot.sessionId === test.sessionId) {
            const previous = entry.snapshot;
            // Monotonic phase/tick watermarks deduplicate even after history eviction.
            if (TERMINAL.includes(previous.state)
                || (previous.state === test.state && test.ticksElapsed <= previous.ticksElapsed)
                || (PHASES.includes(test.state) && PHASES.indexOf(test.state) < PHASES.indexOf(previous.state))) return false;
        } else {
            const seenSessionIds = entry?.seenSessionIds || new Set();
            if (seenSessionIds.has(test.sessionId)) return false;
            seenSessionIds.add(test.sessionId);
            if (seenSessionIds.size > 128) seenSessionIds.delete(seenSessionIds.values().next().value);
            entry = { down: [], up: [], max: 0, snapshot: null, from: 0, receivedAt: now, seenSessionIds };
        }
        const previousView = entry.snapshot ? this.view(entry, now, reducedMotion) : null;
        const samePhase = entry.snapshot?.state === test.state;
        const target = finite(test.actualBandwidth) ? test.actualBandwidth : 0;
        const confirmed = entry.snapshot ? this.view(entry, entry.receivedAt, true) : null;
        entry.failedProgress = confirmed?.progress ?? null;
        entry.failedPhaseProgress = confirmed?.phaseProgress ?? null;
        entry.snapshot = { ...test };
        entry.receivedAt = now;
        entry.from = samePhase ? Math.min(previousView.value, target) : 0;
        if (['DOWNLOAD', 'UPLOAD'].includes(test.state) && finite(test.actualBandwidth)) {
            const points = test.state === 'DOWNLOAD' ? entry.down : entry.up;
            points.push({ ticks: test.ticksElapsed, value: test.actualBandwidth });
            if (points.length > 120) points.shift();
            entry.max = Math.max(entry.max, test.actualBandwidth);
        }
        this.devices.delete(node.id);
        this.devices.set(node.id, entry);
        if (this.devices.size > 256) this.devices.delete(this.devices.keys().next().value);
        return true;
    }

    view(entry, now, reducedMotion = false) {
        const test = entry.snapshot;
        const terminal = TERMINAL.includes(test.state);
        const finished = test.state === 'FINISHED';
        const age = Math.max(0, now - entry.receivedAt);
        const elapsed = Math.min(age, SPEEDTEST_STALE_MS);
        const duration = test.totalTicksPerPhase;
        const pingTicks = Math.min(duration, 60);
        const total = 2 * duration + pingTicks;
        const length = test.state === 'PING' ? pingTicks : duration;
        const offset = test.state === 'UPLOAD' ? pingTicks + duration : test.state === 'DOWNLOAD' ? pingTicks : 0;
        // Never predict a phase transition or successful completion.
        const ticks = Math.min(length, test.ticksElapsed + (terminal || reducedMotion ? 0 : elapsed / 50));
        const progress = finished ? 100 : terminal ? entry.failedProgress : Math.min(99.9, (offset + ticks) / total * 100);
        const target = finite(test.actualBandwidth) ? test.actualBandwidth : 0;
        const value = terminal ? (finite(test.downloadBandwidth) ? test.downloadBandwidth : 0)
            : test.state === 'PING' ? 0 : reducedMotion ? target
                : Math.min(target, target - (target - entry.from) * Math.exp(-elapsed / 350));
        return { value, progress, phaseProgress: finished ? 100 : terminal ? entry.failedPhaseProgress : Math.min(99.9, ticks / length * 100),
            totalSeconds: total / 20, terminal, finished, stale: !terminal && age > SPEEDTEST_STALE_MS,
            waitingPhase: !terminal && ticks >= length,
            activity: terminal || reducedMotion ? 0 : ((entry.receivedAt + elapsed) % 1600) / 1600 };
    }
}

// Shared linear axes, no interpolation/synthetic samples, no fabricated zero baseline.
export function speedtestPlot(entry) {
    const span = points => points.length ? points.at(-1).ticks - points[0].ticks : 0;
    const windowTicks = Math.max(span(entry.down), span(entry.up), 1);
    const scale = entry.max || 1;
    const project = points => points.map(point => ({ x: 292 - (points.at(-1).ticks - point.ticks) / windowTicks * 284,
        y: 102 - point.value / scale * 94 }));
    return { down: project(entry.down), up: project(entry.up), max: entry.max, windowSeconds: windowTicks / 20 };
}
