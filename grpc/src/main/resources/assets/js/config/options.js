////////////////////////////////////////////////////////////////////////////////////////////////////////
// We know that you love 'free', we love it too :)! And so the entire GraphHopper routing engine is not 
// only free but even Open Source! The GraphHopper Directions API is also free for development. 
// Grab an API key and have fun with installing anything: https://graphhopper.com/#directions-api
// Misuse of API keys that you don't own is prohibited and you'll be blocked.
////////////////////////////////////////////////////////////////////////////////////////////////////////

// Easily replace this options.js with an additional file that you prodive as options_prod.js activate via:
// BROWSERIFYSWAP_ENV='production' npm run watch
// see also package.json and https://github.com/thlorenz/browserify-swap
exports.options = {
    with_tiles: true,
    environment: "development",
    routing: {host: 'localhost:8998', api_key: '48531d4f4b6e70e2314489e3244c6c71fa8b984c5690af42'},
    geocoding: {host: '', api_key: ''},
    thunderforest: {api_key: ''},
    omniscale: {api_key: 'graphhopper-k8s-f4f36693'},
    mapilion: {api_key: ''}
};