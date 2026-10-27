import {
    api,
    confirmDialog,
    h,
    metricCard,
    startPage,
    toast,
} from './console.js';

function verdictBar(verdicts) {
    const total = verdicts.cheat_like + verdicts.legit_like + verdicts.uncertain;
    if (total === 0) return h('p', { class: 'card__hint', text: '尚无裁决。' });

    const pct = (n) => `${(n / total) * 100}%`;
    const legend = (colorVar, text) =>
        h(
            'span',
            {},
            h('span', { class: 'dot', style: `background: var(${colorVar})` }),
            text,
        );

    // A segment too small to label still needs its number reachable; the tooltip is that channel, and it
    // also gives the bar a hover target per mark rather than one target for the whole strip.
    const segment = (kind, count) =>
        h('div', {
            class: `bar__seg bar__seg--${kind}`,
            style: `width:${pct(count)}`,
            title: `${count} 条（${((count / total) * 100).toFixed(1)}%）`,
        });

    return h(
        'div',
        { class: 'stack' },
        h(
            'div',
            { class: 'bar', role: 'img', 'aria-label': '裁决分布' },
            segment('cheat', verdicts.cheat_like),
            segment('uncertain', verdicts.uncertain),
            segment('legit', verdicts.legit_like),
        ),
        h(
            'div',
            { class: 'legend' },
            legend('--md-sys-color-verdict-cheat', `作弊样 ${verdicts.cheat_like}`),
            legend('--md-sys-color-verdict-uncertain', `不确定 ${verdicts.uncertain}`),
            legend('--md-sys-color-verdict-legit', `正常样 ${verdicts.legit_like}`),
        ),
    );
}

async function load() {
    const data = await api.overview();
    const metrics = data.metrics;

    document.getElementById('metric-grid').replaceChildren(
        metricCard(
            '当前模型',
            data.model ? data.model.model_id : '无',
            data.model ? data.model.dataset_id : '冷启动：检测不启用',
        ),
        metricCard(
            '样本总量',
            Object.values(metrics.samples_by_tenant).reduce((a, b) => a + b, 0),
        ),
        metricCard('请求数', metrics.requests, `错误率 ${(metrics.error_rate * 100).toFixed(2)}%`),
        metricCard('队列积压', metrics.queue_backlog, '尚未被训练到的样本'),
        metricCard('租户', `${data.tenants.active} / ${data.tenants.total}`, '活跃 / 总数'),
        metricCard('回滚次数', metrics.rollbacks),
    );

    document.getElementById('verdict-body').replaceChildren(verdictBar(metrics.verdicts));

    document.getElementById('model-summary').textContent = data.model
        ? `阈值 cheat ≥ ${data.model.thresholds.cheat_like}，legit ≤ ${data.model.thresholds.legit_like}，后端 ${data.model.trainer}。保留 ${data.retained_versions} 个版本。`
        : '尚未发布任何模型。先录制样本，再重训。';

    const rollback = document.getElementById('rollback-button');
    rollback.disabled = data.retained_versions < 2;
    rollback.title = rollback.disabled ? '没有可回滚的历史模型' : '';
}

document.getElementById('rollback-button').addEventListener('click', async () => {
    if (!(await confirmDialog('回滚模型', '将把当前模型退回上一个版本。确定？', '回滚'))) return;
    try {
        const result = await api.rollback();
        toast(`已回滚到 ${result.restored}`);
        await load();
    } catch (error) {
        toast(error.detail || '回滚失败', 'error');
    }
});

document.getElementById('retrain-button').addEventListener('click', async (event) => {
    const button = event.currentTarget;
    button.disabled = true;
    try {
        const result = await api.retrain();
        toast(
            result.published
                ? `已发布新模型 ${result.published}`
                : `未发布：${result.skipped || '无变化'}`,
        );
        await load();
    } catch (error) {
        toast(error.detail || '重训失败', 'error');
    } finally {
        button.disabled = false;
    }
});

startPage(load);
