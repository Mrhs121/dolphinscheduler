
import { reactive } from 'vue'
import * as Fields from '../fields'
import type { IJsonItem, INodeData, ITaskData } from '../types'

export function useSeaTunnelRestV2({
    projectCode,
    from = 0,
    readonly,
    data
}: {
    projectCode: number
    from?: number
    readonly?: boolean
    data?: ITaskData
}) {
    const model = reactive({
        name: '',
        taskType: 'SEATUNNEL_REST_V2',
        flag: 'YES',
        description: '',
        timeoutFlag: false,
        localParams: [],
        environmentCode: null,
        failRetryInterval: 1,
        failRetryTimes: 0,
        workerGroup: 'default',
        cpuQuota: -1,
        memoryMax: -1,
        delayTime: 0,
        timeout: 30,
        timeoutNotifyStrategy: ['WARN'],
        // 插件私有：全部放 taskParams
        taskParams: {
            jobName: '',
            jobConf: '',
            restUrl: '',
            authToken: '',
            format: 'json',
            logPullLimit: 65536,
            pollIntervalMs: 10000
        }
    } as INodeData)

    return {
        json: [
            Fields.useName(from),
            ...Fields.useTaskDefinition({ projectCode, from, readonly, data, model }),
            Fields.useRunFlag(),
            Fields.useCache(),
            Fields.useDescription(),
            Fields.useTaskPriority(),
            Fields.useWorkerGroup(projectCode),
            Fields.useEnvironmentName(model, !data?.id),
            ...Fields.useTaskGroup(model, projectCode),
            ...Fields.useFailed(),
            ...Fields.useResourceLimit(),
            Fields.useDelayTime(model),
            ...Fields.useTimeoutAlarm(model),

            // 参数表单区
            ...Fields.useSeaTunnelRestV2(model),

            Fields.usePreTasks()
        ] as IJsonItem[],
        model
    }
}
