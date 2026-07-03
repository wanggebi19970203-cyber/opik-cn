from typing import Dict, List

import pydantic


class ConversationThreadItem(pydantic.BaseModel):
    """
    表示对话线程中的单条消息。

    每个 ConversationItem 包含发送者的角色（如 'user'、'assistant'、'system'）和消息内容。
    这种结构化格式使得消息能够在不同的对话接口和评估系统中保持一致的表示。
    """

    role: str
    content: str


class ConversationThread(pydantic.BaseModel):
    """
    表示由多个对话项组成的对话线程。

    此类使用 Pydantic 的 BaseModel 构建，以确保类型验证和数据完整性。
    它维护一个对话项列表，每个项是 `ConversationThreadItem` 类的实例。
    该对话线程允许添加来自不同角色（如 assistant、user 和 system）的消息，
    并提供将对话数据导出为 JSON 可序列化列表的能力。

    Attributes:
        discussion (List[ConversationThreadItem]): 表示角色之间对话的对话项列表。
    """

    discussion: List[ConversationThreadItem] = pydantic.Field(default_factory=list)

    def add_item(self, item: ConversationThreadItem) -> None:
        self.discussion.append(item)

    def add_assistant_message(self, message: str) -> None:
        self.add_item(ConversationThreadItem(role="assistant", content=message))

    def add_user_message(self, message: str) -> None:
        self.add_item(ConversationThreadItem(role="user", content=message))

    def add_system_message(self, message: str) -> None:
        self.add_item(ConversationThreadItem(role="system", content=message))

    def as_json_list(self) -> List[Dict[str, str]]:
        return [item.model_dump() for item in self.discussion]
