const { test } = require('node:test');
const assert = require('node:assert/strict');
const { compare, rate } = require('../../main/resources/META-INF/spring-boot-admin-server-ui/extensions/platform-monitor/monitor.js');
const row = (local, ids) => ({data:{running:true,localMemberId:local,members:ids.map(id=>({id}))}});
test('compares UUID sets independently of ordering',()=>assert.match(compare([row('a',['a','b']),row('b',['b','a'])]),/일치.*2\/2/));
test('detects separated membership',()=>assert.match(compare([row('a',['a']),row('b',['b'])]),/불일치/));
test('missing data never reports healthy',()=>assert.match(compare([row('a',['a']),{}]),/조회 불가/));
test('one observer cannot establish consistency',()=>assert.match(compare([row('a',['a','b'])]),/비교 대상 부족/));
test('rates without sufficient samples are not negative percentages',()=>assert.equal(rate(-1),'표본 부족'));
test('registers two independent pages under operations menu', () => {
  const fs = require('node:fs');
  const vm = require('node:vm');
  const views = [];
  const registry = {addView: v => views.push(v)};
  vm.runInNewContext(fs.readFileSync(require.resolve('../../main/resources/META-INF/spring-boot-admin-server-ui/extensions/platform-monitor/monitor.js'), 'utf8'), {
    SBA: {use: plugin => plugin.install({viewRegistry: registry})}, Vue: {}
  });
  assert.equal(views[0].label, '운영 모니터링');
  assert.deepEqual(views.slice(1).map(v => [v.parent, v.path]), [['platform-monitor', 'circuits'], ['platform-monitor', 'hazelcast']]);
  assert.notEqual(views[1].component, views[2].component);
});
