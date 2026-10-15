-- ============================================================
-- 校园服务意图树种子数据
-- 设计源头：intents/*.properties（本项目自定义意图树）
-- 运行时来源：t_intent_node 表（内存 → Redis → DB 三级加载）
-- kb_id = 2110574442286424064（学生守则知识库，collection_name = school）
-- kind: 0=知识库检索节点  2=MCP 工具节点（绑定 mcp_tool_id，唯一索引约束）
-- enabled: 1=启用  0=停用（工具节点待任务D实现后开启；文档未写的域节点停用）
-- ============================================================

INSERT INTO t_intent_node
  (id, kb_id, intent_code, name, level, parent_code, description, examples,
   collection_name, collection_names, top_k, mcp_tool_id, require_confirm,
   kind, sort_order, enabled, create_by, update_by)
VALUES
  -- 根节点：校园服务
  ('2111015000000001001', '2110574442286424064', 'campus', '校园服务', 0, NULL,
   '校园相关的服务与制度咨询总入口',
   NULL,
   'school', '["school"]'::jsonb, NULL, NULL, 0,
   0, 0, 1, 'admin', 'admin'),

  -- 图书馆服务（知识库检索节点）
  ('2111015000000001002', '2110574442286424064', 'campus-library', '图书馆服务', 1, 'campus',
   '图书馆开放时间、借阅数量与期限、续借规则、超期停借处理、图书遗失赔偿、预约与荐购',
   '书没看完怎么续借？|借书最多能借几本？|超期了会怎么样？|图书丢了怎么赔？|图书馆几点开门？|书被别人借走了怎么预约？',
   'school', '["school"]'::jsonb, NULL, NULL, 0,
   0, 10, 1, 'admin', 'admin'),

  -- 我的借阅查询（MCP 工具节点，任务D实现 library_query 后启用）
  ('2111015000000001003', NULL, 'campus-library-account', '我的借阅查询', 2, 'campus-library',
   '查询当前登录学生的在借图书清单和应还时间，属于个人实时数据，不走知识库检索',
   '我借的书什么时候到期？|我现在有几本书没还？|帮我查一下我的借阅记录',
   NULL, '[]'::jsonb, NULL, 'library_query', 0,
   2, 11, 0, 'admin', 'admin'),

  -- 选课与课表（知识库检索节点）
  ('2111015000000001004', '2110574442286424064', 'campus-course', '选课与课表', 1, 'campus',
   '选课时间安排、选课规则与学分上下限、先修课限制、退改选规则、课表查询方式',
   '一学期最多能选多少学分？|退课会影响成绩吗？|错过选课时间怎么办？|选的课人数满了怎么办？|怎么查看我的课表？',
   'school', '["school"]'::jsonb, NULL, NULL, 0,
   0, 20, 1, 'admin', 'admin'),

  -- 我的课表查询（MCP 工具节点，任务D实现 course_query 后启用）
  ('2111015000000001005', NULL, 'campus-course-schedule', '我的课表查询', 2, 'campus-course',
   '查询当前登录学生的上课时间表，属于个人实时数据，不走知识库检索',
   '我周三有什么课？|明天第一节是什么课？|这周我的课表是什么？',
   NULL, '[]'::jsonb, NULL, 'course_query', 0,
   2, 21, 0, 'admin', 'admin'),

  -- 考试与成绩（文档未写，暂停用）
  ('2111015000000001006', '2110574442286424064', 'campus-exam', '考试与成绩', 1, 'campus',
   '考试安排、成绩查询与复查规则（待补充文档后开启）',
   '考试安排在哪看？|对成绩有异议怎么复查？',
   'school', '["school"]'::jsonb, NULL, NULL, 0,
   0, 30, 0, 'admin', 'admin'),

  -- 校园卡服务（文档未写，暂停用）
  ('2111015000000001007', '2110574442286424064', 'campus-card', '校园卡服务', 1, 'campus',
   '校园卡充值、挂失、补办、消费查询（待补充文档后开启）',
   '校园卡丢了怎么挂失？|校园卡怎么充值？',
   'school', '["school"]'::jsonb, NULL, NULL, 0,
   0, 40, 0, 'admin', 'admin'),

  -- 宿舍服务（文档未写，暂停用）
  ('2111015000000001008', '2110574442286424064', 'campus-dorm', '宿舍服务', 1, 'campus',
   '宿舍报修、门禁、调宿规则（待补充文档后开启）',
   '宿舍空调坏了怎么报修？|门禁时间是几点？',
   'school', '["school"]'::jsonb, NULL, NULL, 0,
   0, 50, 0, 'admin', 'admin');
