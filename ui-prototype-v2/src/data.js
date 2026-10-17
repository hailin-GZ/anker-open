import { ChatsCircle, Compass, Tote, UsersThree, Notebook, DotsThree } from '@phosphor-icons/react';

export const categories = [
  { id: 'daily', name: '日常交流', subtitle: '把平常的话，好好留住', color: 'mint', icon: ChatsCircle },
  { id: 'travel', name: '问路出行', subtitle: '每一程，都有回应', color: 'butter', icon: Compass },
  { id: 'work', name: '工作学习', subtitle: '重要的事，慢慢记', color: 'lilac', icon: Notebook },
  { id: 'shopping', name: '购物办事', subtitle: '小事也可以很顺利', color: 'peach', icon: Tote },
  { id: 'family', name: '家人朋友', subtitle: '熟悉的声音，在这里', color: 'blue', icon: UsersThree },
  { id: 'other', name: '其他对话', subtitle: '还没分类的小片段', color: 'stone', icon: DotsThree },
];
export const conversations = [
  { id: 1, category: 'daily', title: '一起去公园走走', date: '2026-10-17', time: '10:24', duration: '3分12秒', mood: '轻松', speaker: '朋友', text: '今天天气不错，要不要一起去公园走走？', answer: '好呀，我们慢慢走，顺便买杯咖啡。' },
  { id: 2, category: 'daily', title: '楼下新开的咖啡店', date: '2026-10-16', time: '15:08', duration: '2分08秒', mood: '开心', speaker: '朋友', text: '楼下新开的咖啡店，你想一起去试试吗？', answer: '想去！我们周末一起吧。' },
  { id: 3, category: 'travel', title: '去美术馆的路', date: '2026-10-16', time: '09:30', duration: '1分46秒', mood: '平和', speaker: '路人', text: '沿这条路直走，在第二个路口左转就能看见美术馆。', answer: '好的，谢谢你，我记住了。' },
  { id: 4, category: 'work', title: '周一的小组讨论', date: '2026-10-15', time: '14:10', duration: '8分20秒', mood: '专注', speaker: '同学', text: '下周一下午三点，我们一起讨论一下设计方案。', answer: '收到，我会把需要讨论的内容提前整理好。' },
  { id: 5, category: 'shopping', title: '取回修好的眼镜', date: '2026-10-15', time: '11:00', duration: '2分16秒', mood: '平和', speaker: '店员', text: '您的眼镜已经修好了，周日下午六点前可以过来取。', answer: '好的，我周日下午过来，谢谢。' },
  { id: 6, category: 'family', title: '周末回家吃饭', date: '2026-10-14', time: '18:42', duration: '5分03秒', mood: '温暖', speaker: '家人', text: '周六回来吃饭吧，给你做了最爱吃的菜。', answer: '好，我会早点回来，一起准备晚饭。' },
  { id: 7, category: 'family', title: '记得带一件外套', date: '2026-10-13', time: '08:15', duration: '1分10秒', mood: '关心', speaker: '家人', text: '今天晚上有点凉，出门记得带一件外套。', answer: '我带上了，你也注意保暖。' },
];
export const seedMarks = [
  { id: 'm1', type: 'quote', title: '平常的一句话，也值得珍藏。', body: '“周六回来吃饭吧，给你做了最爱吃的菜。”', source: '家人朋友 · 周末回家吃饭', color: 'lilac', pinned: true, done: false },
  { id: 'm2', type: 'reminder', title: '去取修好的眼镜', body: '记得带上取件单，店铺六点关门。', due: '2026-10-18T16:30', color: 'butter', done: false },
  { id: 'm3', type: 'todo', title: '整理周一讨论的设计稿', body: '把两个方案都准备好。', source: '工作学习 · 周一的小组讨论', color: 'mint', done: false },
];
export const replyOptions = [
  { tone: '自然回应', text: '好呀，我们慢慢走，顺便买杯咖啡。', color: 'mint' },
  { tone: '表达期待', text: '听起来不错！我也正想出去透透气。', color: 'butter' },
  { tone: '确认时间', text: '可以呀，我们几点出发比较合适？', color: 'blue' },
  { tone: '礼貌婉拒', text: '谢谢你约我，今天想休息，下次一起吧。', color: 'lilac' },
];
