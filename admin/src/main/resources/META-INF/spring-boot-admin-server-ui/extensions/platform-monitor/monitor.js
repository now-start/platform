(function () {
  'use strict';
  function compare(rows) {
    const good = rows.filter(r => r.data && r.data.running);
    const signatures = new Set(good.map(r => r.data.members.map(m => m.id).sort().join(',')));
    if (signatures.size > 1) return '멤버 목록 불일치 · 분리 또는 가입/탈퇴 확인 필요';
    if (good.length !== rows.length) return '일부 인스턴스 조회 불가';
    if (good.length < 2) return '비교 대상 부족';
    const members = new Set(good.flatMap(r => r.data.members.map(m => m.id)));
    const observed = new Set(good.map(r => r.data.localMemberId));
    return `관측 목록 일치 · 멤버 관측 ${observed.size}/${members.size}`;
  }
  function rate(value) { return typeof value === 'number' && value >= 0 ? `${value.toFixed(1)}%` : '표본 부족'; }
  if (typeof module !== 'undefined') module.exports = { compare, rate };
  if (!globalThis.SBA || !globalThis.Vue) return;
  const { h, ref, computed, onMounted, onUnmounted, resolveComponent } = globalThis.Vue;
  const cell = text => h('td', String(text ?? '—'));
  const table = (headers, rows) => h('div', { class: 'pm-scroll' }, [h('table', [h('thead', h('tr', headers.map(x => h('th', x)))), h('tbody', rows)])]);
  const createPage = (kind, title, endpoint) => ({
    setup() {
      const { applications, error } = SBA.useApplicationStore();
      const rows = ref([]), busy = ref(false), updated = ref('아직 조회하지 않음');
      let alive = true, timer;
      const groups = computed(() => {
        const result = Object.create(null);
        rows.value.filter(r => r.kind === 'hazelcast').forEach(r => {
          const key = r.data?.clusterName || `${r.name} (클러스터 이름 미확인)`;
          (result[key] ||= []).push(r);
        });
        return result;
      });
      async function refresh() {
        if (busy.value) return;
        busy.value = true;
        const jobs = (applications.value || []).flatMap(app => (app.instances || []).map(instance => ({ instance, kind, endpoint })));
        const next = [];
        let cursor = 0;
        await Promise.all(Array.from({ length: Math.min(4, jobs.length) }, async () => {
          while (alive && cursor < jobs.length) {
            const { instance, kind, endpoint } = jobs[cursor++];
            const row = { id: instance.id, name: instance.registration.name, url: instance.registration.serviceUrl, kind };
            if (!instance.hasEndpoint(endpoint)) row.message = '미지원 · 엔드포인트 없음';
            else {
              try {
                const response = await instance.axios.get(`actuator/${endpoint}`, { timeout: 5000 });
                const data = response.data;
                if (kind === 'circuit' ? !Array.isArray(data.circuits) : (!Array.isArray(data.members) || !data.clusterName || !data.localMemberId)) throw new Error('invalid snapshot');
                row.data = data;
              } catch (_) { row.message = '조회 불가 · 연결/권한/응답 확인'; }
            }
            next.push(row);
          }
        }));
        if (alive) {
          rows.value = next.sort((a, b) => `${a.name}${a.id}`.localeCompare(`${b.name}${b.id}`));
          updated.value = new Date().toLocaleTimeString();
          busy.value = false;
        }
      }
      onMounted(() => { refresh(); timer = setInterval(refresh, 10000); });
      onUnmounted(() => { alive = false; clearInterval(timer); });
      function circuitView() {
        const supported = rows.value.filter(r => r.kind === 'circuit');
        return [h('p', { class: 'pm-note' }, '서킷은 각 Gateway JVM에서 독립적으로 동작합니다. 아래 수치는 현재 슬라이딩 윈도우이며, HTTP 5xx는 기본 설정에서 실패로 집계되지 않습니다.'),
          table(['애플리케이션 / 인스턴스', '서킷', '상태', '실패율', '느린 호출', '성공 / 실패 / 차단'], supported.flatMap(r => {
            const label = `${r.name} · ${r.id}`;
            if (!r.data || !r.data.circuits.length) return [h('tr', [cell(label), cell('—'), cell(r.message || '서킷 생성 전 · 호출 없음'), cell('—'), cell('—'), cell('—')])];
            return r.data.circuits.map(c => h('tr', [cell(label), cell(c.name), h('td', h('span', { class: `pm-state pm-${c.state.toLowerCase()}` }, c.state)), cell(rate(c.failureRate)), cell(rate(c.slowCallRate)), cell(`${c.successfulCalls} / ${c.failedCalls} / ${c.notPermittedCalls}`)]));
          }))];
      }
      function hazelcastView() {
        return [h('p', { class: 'pm-note' }, '각 인스턴스가 관측한 멤버 UUID 집합을 비교합니다. 이름이 같아도 같은 클러스터라는 보장은 없습니다. 가입/탈퇴 중의 일시적 불일치와 네트워크 분리를 구분하려면 반복 관측이 필요합니다.'),
          ...Object.entries(groups.value).map(([name, group]) => h('section', { class: 'pm-group' }, [h('h2', name), h('p', { class: 'pm-note' }, compare(group)),
            table(['인스턴스', '상태', '로컬 멤버 UUID', '관측 멤버 · 주소'], group.map(r => h('tr', [cell(`${r.name} · ${r.id}`), cell(r.message || (r.data.running ? r.data.clusterState : 'STOPPED')), cell(r.data?.localMemberId), h('td', r.data ? r.data.members.map(m => h('div', { class: 'pm-member' }, `${m.id} · ${m.address}${m.lite ? ' (lite)' : ''}`)) : '—')])))]))];
      }
      return () => h('main', { class: 'platform-monitor' }, [
        h('div', { class: 'pm-heading' }, [h('div', [h('p', '운영 모니터링'), h('h1', title), h('p', `10초마다 조회 · 마지막 완료 ${updated.value}`)]), h('button', { disabled: busy.value, onClick: refresh }, busy.value ? '조회 중…' : '새로고침')]),
        error.value ? h('p', { role: 'alert', class: 'pm-error' }, 'Admin 인스턴스 목록을 가져오지 못했습니다. 표시된 정보가 최신이 아닐 수 있습니다.') : null,
        !rows.value.length ? h('p', '등록 인스턴스를 기다리는 중입니다.') : (kind === 'circuit' ? circuitView() : hazelcastView()),
        h('p', { class: 'pm-foot' }, '읽기 전용 · Admin에 등록된 인스턴스만 관측 · 조회 실패를 정상으로 간주하지 않습니다. 전체 클러스터의 정상 여부나 과거 이력을 보장하지 않습니다.')
      ]);
    }
  });
  SBA.use({ install({ viewRegistry }) {
    viewRegistry.addView({
      name: 'platform-monitor', path: '/platform-monitor', label: '운영 모니터링', order: 1000,
      component: { render: () => h(resolveComponent('router-view'), null, { default: ({ Component }) => Component ? h(Component) : h('main', { class: 'platform-monitor' }, [h('h1', '운영 모니터링'), h('p', '상단 운영 모니터링 메뉴에서 서킷 브레이커 또는 Hazelcast를 선택하세요.')]) }) }
    });
    viewRegistry.addView({
      name: 'platform-monitor-circuits', parent: 'platform-monitor', path: 'circuits', label: '서킷 브레이커', order: 0,
      component: createPage('circuit', '서킷 브레이커', 'circuitbreakersnapshot')
    });
    viewRegistry.addView({
      name: 'platform-monitor-hazelcast', parent: 'platform-monitor', path: 'hazelcast', label: 'Hazelcast', order: 1,
      component: createPage('hazelcast', 'Hazelcast', 'hazelcastcluster')
    });
  } });
})();
