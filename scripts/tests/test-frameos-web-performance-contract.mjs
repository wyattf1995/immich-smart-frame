import assert from 'node:assert/strict';import fs from 'node:fs';
const panel=fs.readFileSync('examples/frameos/frameos-touchscreen-panel.html','utf8');const bridge=fs.readFileSync('frameos/app/src/main/assets/frame-photo-bridge/photos.js','utf8');
assert.match(panel,/homeProjectionCache\?\.states === live\.states/);assert.match(panel,/calendarIds = homeProjectionCache/);
assert.match(bridge,/captureMutationTimer/);assert.match(bridge,/setTimeout\(\(\) => \{ captureMutationTimer = 0; scheduleCapture\(\); \}, 50\)/);assert.match(bridge,/\.observe\(document\.body/);
console.log('FrameOS web performance contract passed');
