export function metadata(overrides = {}) {
    return { ready: true, revision: 'r1', originX: 0, originZ: 0, blocksPerPixel: 1,
        width: 512, height: 512, empty: false, updatedAt: 1234, ...overrides };
}

// Only IHDR is needed by the preflight tests; the injected decoder models browser validation.
export function pngBlob(width = 512, height = 512) {
    const bytes = new Uint8Array(33);
    bytes.set([137, 80, 78, 71, 13, 10, 26, 10]);
    const view = new DataView(bytes.buffer);
    view.setUint32(8, 13);
    view.setUint32(12, 0x49484452);
    view.setUint32(16, width);
    view.setUint32(20, height);
    return new Blob([bytes], { type: 'image/png' });
}

export function pngResponse(overrides = {}, extraHeaders = {}) {
    const data = metadata(overrides);
    return { status: 200, headers: new Headers({ 'Content-Type': 'image/png', 'X-Map-Revision': data.revision,
        'X-Map-Origin-X': String(data.originX), 'X-Map-Origin-Z': String(data.originZ),
        'X-Map-Scale': String(data.blocksPerPixel), 'X-Map-Width': String(data.width), 'X-Map-Height': String(data.height),
        ETag: `"${data.revision}"`, ...extraHeaders }), blob: async () => pngBlob(data.width, data.height) };
}
