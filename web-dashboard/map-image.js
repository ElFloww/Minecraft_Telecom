import { retryDelay } from './coverage.js';

export const MAP_IMAGE_LIMIT = 2048;
export const MAP_IMAGE_BYTES = MAP_IMAGE_LIMIT ** 2 * 4;

function imageGeometry(headers) {
    const result = { revision: headers.get('X-Map-Revision') };
    for (const [key, header] of Object.entries({ originX: 'Origin-X', originZ: 'Origin-Z',
        blocksPerPixel: 'Scale', width: 'Width', height: 'Height' })) {
        const value = headers.get(`X-Map-${header}`);
        if (!/^-?\d+$/.test(value ?? '')) throw new Error('Invalid map geometry');
        result[key] = Number(value);
        if (!Number.isSafeInteger(result[key])) throw new Error('Invalid map geometry');
    }
    if (!result.revision || result.blocksPerPixel < 1 || result.blocksPerPixel > 2147483647
        || result.originX < -2147483648 || result.originX > 2147483647
        || result.originZ < -2147483648 || result.originZ > 2147483647
        || result.width < 1 || result.width > MAP_IMAGE_LIMIT
        || result.height < 1 || result.height > MAP_IMAGE_LIMIT) throw new Error('Invalid map geometry');
    return result;
}

export class MapImageStore {
    constructor(fetcher, { decode = blob => createImageBitmap(blob), now = () => Date.now() } = {}) {
        this.fetcher = fetcher;
        this.decode = decode;
        this.now = now;
        this.generation = 0;
        this.pending = false;
        this.invalidate();
    }

    invalidate() {
        this.generation++;
        this.snapshot?.image.close();
        this.snapshot = null;
        this.mapId = null;
        this.metadata = null;
        this.completedRevision = null;
        this.retryAt = 0;
        this.message = 'Carte physique : en attente du snapshot global.';
        // The old request/decode retains its slot until it settles, even after a world switch.
    }

    select(mapId, metadata) {
        if (mapId !== this.mapId) {
            this.invalidate();
            this.mapId = mapId;
        }
        this.metadata = metadata;
        if (metadata?.error) this.message = 'Carte physique indisponible : verifier les captures enregistrees. Dernier snapshot conserve.';
        else if (metadata?.empty) this.message = 'Carte physique vide : aucune capture disponible.';
        else if (!metadata?.ready) this.message = 'Carte physique : snapshot en preparation.';
    }

    get bytes() { return this.snapshot ? this.snapshot.width * this.snapshot.height * 4 : 0; }

    async tick() {
        const metadata = this.metadata;
        if (this.pending || this.now() < this.retryAt || typeof this.mapId !== 'string' || !this.mapId
            || metadata?.ready !== true || metadata.empty !== false
            || typeof metadata.revision !== 'string' || !metadata.revision
            || metadata.revision === this.completedRevision || metadata.revision === this.snapshot?.revision) return;
        this.pending = true;
        this.message = 'Carte physique : chargement du snapshot global.';
        const generation = this.generation, mapId = this.mapId, revision = metadata.revision;
        const current = () => generation === this.generation;
        let image;
        try {
            const headers = new Headers();
            if (this.snapshot?.etag) headers.set('If-None-Match', this.snapshot.etag);
            const response = await this.fetcher(`/api/map-image?map=${encodeURIComponent(mapId)}`, {
                headers, isCurrent: current,
            });
            if (!current()) return;
            // Optional extra defence; the server must also guard the requested map identity.
            const responseMap = response.headers?.get('X-Map-Id');
            if (responseMap != null && responseMap !== mapId) throw new Error('Map identity mismatch');
            if (response.status === 200) {
                if (response.headers.get('Content-Type')?.split(';')[0].trim().toLowerCase() !== 'image/png') {
                    throw new Error('Expected PNG');
                }
                const geometry = imageGeometry(response.headers);
                const blob = await response.blob();
                if (!current()) return;
                // Inspect IHDR before allocating any decoded pixels, not just the HTTP dimensions.
                if (blob.size < 33 || blob.size > MAP_IMAGE_BYTES + 1024 * 1024) throw new Error('Invalid PNG size');
                const prefix = new Uint8Array(await blob.slice(0, 33).arrayBuffer());
                const view = new DataView(prefix.buffer, prefix.byteOffset, prefix.byteLength);
                if (![137, 80, 78, 71, 13, 10, 26, 10].every((byte, i) => prefix[i] === byte)
                    || view.getUint32(8) !== 13 || view.getUint32(12) !== 0x49484452
                    || view.getUint32(16) !== geometry.width || view.getUint32(20) !== geometry.height) {
                    throw new Error('PNG dimensions do not match headers');
                }
                if (!current()) return;
                image = await this.decode(blob);
                if (!current()) return;
                if (image.width !== geometry.width || image.height !== geometry.height) throw new Error('Invalid decoded dimensions');
                const previous = this.snapshot;
                this.snapshot = { ...geometry, image, etag: response.headers.get('ETag') };
                image = null;
                previous?.image.close();
                this.completedRevision = revision;
                this.retryAt = 0;
                this.message = 'Carte physique globale : zones transparentes inconnues.';
            } else if (response.status === 304 && this.snapshot) {
                this.completedRevision = revision;
                this.retryAt = 0;
                this.message = 'Carte physique inchangee.';
            } else if (response.status === 204) {
                this.completedRevision = revision;
                this.retryAt = this.now() + retryDelay(response.headers?.get('Retry-After'), 6, this.now());
                this.message = 'Carte physique vide : aucune nouvelle capture disponible.';
            } else if ([202, 429, 503, 504].includes(response.status)) {
                this.retryAt = this.now() + retryDelay(response.headers?.get('Retry-After'), 1, this.now());
                this.message = response.status === 202 ? 'Carte physique en attente (jeu en pause ou occupe).'
                    : 'Carte physique : serveur occupe, lecture differee.';
            } else {
                throw new Error(`Map image HTTP ${response.status}`);
            }
        } catch (error) {
            if (current()) {
                this.retryAt = error.deferred ? error.retryAt : this.now() + 3000;
                this.message = 'Carte physique indisponible : dernier snapshot valide conserve.';
            }
        } finally {
            image?.close();
            this.pending = false;
        }
    }

    draw(ctx, pan, zoom) {
        const snapshot = this.snapshot;
        if (!snapshot) return;
        ctx.imageSmoothingEnabled = false;
        ctx.drawImage(snapshot.image, snapshot.originX * zoom + pan.x, snapshot.originZ * zoom + pan.y,
            snapshot.width * snapshot.blocksPerPixel * zoom, snapshot.height * snapshot.blocksPerPixel * zoom);
    }
}
