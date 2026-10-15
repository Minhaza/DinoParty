import type { TelephoneSource } from '../types'
import { TelephoneAssignmentCard } from './TelephoneAssignmentCard'
import { TelephonePromptEditor, type TelephonePromptEditorProps } from './TelephonePromptEditor'

export interface TelephoneDescriptionEditorProps extends Omit<TelephonePromptEditorProps, 'mode'> {
  source: TelephoneSource | null | undefined
}

/** DESCRIBE step: only the assigned drawing, and a text card for what it shows. */
export function TelephoneDescriptionEditor({ source, ...editor }: TelephoneDescriptionEditorProps) {
  return (
    <div className="grid min-w-0 grid-cols-1 gap-3 lg:grid-cols-12">
      <div className="min-w-0 lg:col-span-7">
        <TelephoneAssignmentCard source={source} />
      </div>
      <div className="min-w-0 lg:col-span-5">
        <TelephonePromptEditor mode="describe" {...editor} />
      </div>
    </div>
  )
}
