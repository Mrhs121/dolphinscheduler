/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import type { IJsonItem } from '../types'
import { useCustomParams, useNamespace } from '.'
import { useI18n } from 'vue-i18n'
import { computed } from 'vue'
export function useKubeflow(model: { [field: string]: any }): IJsonItem[] {
  const { t } = useI18n()
  const mainClassSpan = computed(() =>
    model.programType === 'PYTHON' || model.programType === 'SQL' ? 0 : 24
  )
  const sparkClassSpan = computed(() =>
    model.programType === 'PYTHON' || model.programType === 'JAVA' ? 0 : 12
  )

  return [
    useNamespace(),
    {
      type: 'select',
      field: 'programType',
      span: 12,
      name: t('project.node.program_type'),
      options: PROGRAM_TYPES,
      value: model.programType
    },
    {
      type: 'input',
      field: 'mainClass',
      span: mainClassSpan,
      name: t('project.node.main_class'),
      props: {
        placeholder: t('project.node.main_class_tips')
      },
      validate: {
        trigger: ['input', 'blur'],
        required: model.programType !== 'PYTHON',
        validator(validate: any, value: string) {
          if (model.programType !== 'PYTHON' && !value) {
            return new Error(t('project.node.main_class_tips'))
          }
        }
      }
    },
    {
      type: 'input-number',
      field: 'driverCores',
      name: t('project.node.driver_cores'),
      span: sparkClassSpan,
      props: {
        placeholder: t('project.node.driver_cores_tips'),
        min: 1
      },
      validate: {
        trigger: ['input', 'blur'],
        required: true,
        validator(validate: any, value: string) {
          if (!value) {
            return new Error(t('project.node.driver_cores_tips'))
          }
        }
      }
    },
    {
      type: 'input',
      field: 'driverMemory',
      name: t('project.node.driver_memory'),
      span: sparkClassSpan,
      props: {
        placeholder: t('project.node.driver_memory_tips')
      },
      validate: {
        trigger: ['input', 'blur'],
        required: true,
        validator(validate: any, value: string) {
          if (!value) {
            return new Error(t('project.node.driver_memory_tips'))
          }
          if (!Number.isInteger(parseInt(value))) {
            return new Error(
              t('project.node.driver_memory') +
                t('project.node.positive_integer_tips')
            )
          }
        }
      }
    },
    {
      type: 'input-number',
      field: 'numExecutors',
      name: t('project.node.executor_number'),
      span: sparkClassSpan,
      props: {
        placeholder: t('project.node.executor_number_tips'),
        min: 1
      },
      validate: {
        trigger: ['input', 'blur'],
        required: true,
        validator(validate: any, value: string) {
          if (!value) {
            return new Error(t('project.node.executor_number_tips'))
          }
        }
      }
    },
    {
      type: 'input',
      field: 'executorMemory',
      name: t('project.node.executor_memory'),
      span: sparkClassSpan,
      props: {
        placeholder: t('project.node.executor_memory_tips')
      },
      validate: {
        trigger: ['input', 'blur'],
        required: true,
        validator(validate: any, value: string) {
          if (!value) {
            return new Error(t('project.node.executor_memory_tips'))
          }
          if (!Number.isInteger(parseInt(value))) {
            return new Error(
              t('project.node.executor_memory_tips') +
                t('project.node.positive_integer_tips')
            )
          }
        }
      }
    },
    {
      type: 'input-number',
      field: 'executorCores',
      name: t('project.node.executor_cores'),
      span: sparkClassSpan,
      props: {
        placeholder: t('project.node.executor_cores_tips'),
        min: 1
      },
      validate: {
        trigger: ['input', 'blur'],
        required: true,
        validator(validate: any, value: string) {
          if (!value) {
            return new Error(t('project.node.executor_cores_tips'))
          }
        }
      }
    },
    {
      type: 'editor',
      field: 'yamlContent',
      name: 'yamlContent',
      props: {
        language: 'yaml'
      },
      validate: {
        trigger: ['input', 'trigger'],
        required: true,
        message: 'requestJson'
      }
    },
    {
      type: 'editor',
      field: 'datasources',
      name: 'datasources',
      props: {
        language: 'json'
      },
      validate: {
        trigger: ['input', 'trigger'],
        required: true,
        message: 'requestJson'
      }
    },
    ...useCustomParams({ model, field: 'localParams', isSimple: false })
  ]
}

export const PROGRAM_TYPES = [
  {
    label: 'JAVA',
    value: 'JAVA'
  },
  {
    label: 'PYTHON',
    value: 'PYTHON'
  },
  {
    label: 'SQL',
    value: 'SQL'
  }
]
