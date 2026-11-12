import { Fiber } from './kernel.mjs'
const log = (...a) => console.log(...a)

const root = new Fiber()

function db(fiber) {
  log('db: connect')
  return () => log('db: close')
}
function server(fiber) {
  log('server: listen')
  fiber.effect(() => { log('server: add route /a'); return () => log('server: remove route /a') })
  fiber.use(function child() { log('  child: start'); return () => log('  child: stop') })
  return () => log('server: stop')
}

root.use(db)
const s = root.use(server)

log('--- dispose server only')
s()
log('--- dispose root')
root.dispose()
