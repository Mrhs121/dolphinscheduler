import type { IJsonItem } from '../types'

export function useSeaTunnelRestV2(model: any): IJsonItem[] {
    model.taskParams ??= {}
    Object.assign(model, {
        jobName: model.jobName ?? '',
        jobConf: model.jobConf ?? '',
        restUrl: model.restUrl ?? '',
        authToken: model.authToken ?? '',
        format: model.format ?? 'json', // json | hocon
        logPullLimit: model.logPullLimit ?? 65536,
        pollIntervalMs: model.pollIntervalMs ?? 10000
    })
    return [
        {
            type: 'input',
            field: 'jobName',
            name: '作业名称',
            props: { placeholder: '如：st_sync_doris' },
            validate: { required: true, trigger: ['input', 'blur'] }
        },
        {
            type: 'select',
            field: 'format',
            name: '配置格式',
            options: [
                { label: 'JSON', value: 'json' },
                { label: 'HOCON', value: 'hocon' }
            ],
            props: { clearable: true, placeholder: '不选则后端自动识别' }
        },
        {
            type: 'input',
            field: 'restUrl',
            name: 'REST URL',
            props: { placeholder: 'http://seatunnel-master:8080（可留空）' }
        },
        {
            type: 'editor',
            field: 'jobConf',
            name: '作业配置',
        },
        {
            type: 'input-number',
            field: 'logPullLimit',
            name: '日志拉取上限(Byte)',
            props: { min: 1024, step: 1024 }
        },
        {
            type: 'input-number',
            field: 'pollIntervalMs',
            name: '轮询间隔(ms)',
            props: { min: 1000, step: 1000 }
        }
    ]
}
