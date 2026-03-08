require('http').createServer((req, res) => {
  res.writeHead(200, {'Content-Type': 'text/plain'});
  res.end('Hello from Node on Android!');
}).listen(18765, () => {
  console.log('HTTP_SERVER_OK:18765');
});
