const fs = require('fs');
const buffer = fs.readFileSync('tile.png');
// PNG signature + IHDR
console.log(buffer.slice(0, 30));
