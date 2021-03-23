package com.sxdx.workflow.activiti.rest.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.sxdx.common.config.GlobalConfig;
import com.sxdx.common.entity.AuthUser;
import com.sxdx.common.entity.CurrentUser;
import com.sxdx.common.exception.base.CommonException;
import com.sxdx.common.util.Page;
import com.sxdx.common.util.workFlowUtil;
import com.sxdx.workflow.activiti.rest.config.ICustomProcessDiagramGenerator;
import com.sxdx.workflow.activiti.rest.config.WorkflowConstants;
import com.sxdx.workflow.activiti.rest.entity.vo.AcExecutionEntityImpl;
import com.sxdx.workflow.activiti.rest.service.ProcessService;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.implementation.bytecode.Throw;
import org.activiti.bpmn.model.*;
import org.activiti.bpmn.model.Process;
import org.activiti.engine.*;
import org.activiti.engine.history.HistoricActivityInstance;
import org.activiti.engine.history.HistoricProcessInstance;
import org.activiti.engine.impl.RepositoryServiceImpl;
import org.activiti.engine.impl.UserQueryImpl;
import org.activiti.engine.impl.cmd.NeedsActiveTaskCmd;
import org.activiti.engine.impl.interceptor.Command;
import org.activiti.engine.impl.interceptor.CommandContext;
import org.activiti.engine.impl.persistence.entity.*;
import org.activiti.engine.impl.util.ProcessDefinitionUtil;
import org.activiti.engine.repository.ProcessDefinition;
import org.activiti.engine.repository.ProcessDefinitionQuery;
import org.activiti.engine.runtime.Execution;
import org.activiti.engine.runtime.ExecutionQuery;
import org.activiti.engine.runtime.ProcessInstance;
import org.activiti.engine.task.Comment;
import org.activiti.engine.task.DelegationState;
import org.activiti.engine.task.Task;
import org.activiti.engine.task.TaskQuery;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ProcessServiceImpl implements ProcessService {

    @Autowired
    private RepositoryService repositoryService;
    @Autowired
    private HistoryService historyService;
    @Autowired
    private ProcessEngine processEngine;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private FormService formService;
    @Autowired
    private IdentityService identityService;
    @Autowired
    private TaskService taskService;
    @Autowired
    private ManagementService managementService;

    public void readResource(String pProcessInstanceId, HttpServletResponse response)
            throws Exception {
        // 设置页面不缓存
        response.setHeader("Pragma", "No-cache");
        response.setHeader("Cache-Control", "no-cache");
        response.setDateHeader("Expires", 0);

        String processDefinitionId = "";
        ProcessInstance processInstance = runtimeService.createProcessInstanceQuery().processInstanceId(pProcessInstanceId).singleResult();
        if(processInstance == null) {
            HistoricProcessInstance historicProcessInstance = historyService.createHistoricProcessInstanceQuery().processInstanceId(pProcessInstanceId).singleResult();
            processDefinitionId = historicProcessInstance.getProcessDefinitionId();
        } else {
            processDefinitionId = processInstance.getProcessDefinitionId();
        }
        ProcessDefinitionQuery pdq = repositoryService.createProcessDefinitionQuery();
        ProcessDefinition pd = pdq.processDefinitionId(processDefinitionId).singleResult();

        String resourceName = pd.getDiagramResourceName();

        if(resourceName.endsWith(".png") && StringUtils.isEmpty(pProcessInstanceId) == false)
        {
            getActivitiProccessImage(pProcessInstanceId,response);
            //ProcessDiagramGenerator.generateDiagram(pde, "png", getRuntimeService().getActiveActivityIds(processInstanceId));
        }
        else
        {
            // 通过接口读取
            InputStream resourceAsStream = repositoryService.getResourceAsStream(pd.getDeploymentId(), resourceName);

            // 输出资源内容到相应对象
            byte[] b = new byte[1024];
            int len = -1;
            while ((len = resourceAsStream.read(b, 0, 1024)) != -1) {
                response.getOutputStream().write(b, 0, len);
            }
        }
    }

    @Override
    public void image(String pProcessInstanceId, HttpServletResponse response) throws Exception {
        try {
            InputStream is = this.getDiagram(pProcessInstanceId);
            if (is == null)
                return;

            response.setContentType("image/png");

            BufferedImage image = ImageIO.read(is);
            OutputStream out = response.getOutputStream();
            ImageIO.write(image, "png", out);

            is.close();
            out.close();
        } catch (Exception ex) {
            log.error("查看流程图失败", ex);
        }
    }

    //service层代码
    public InputStream getDiagram(String processInstanceId) {
        //获得流程实例
        ProcessInstance processInstance = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        String processDefinitionId = StringUtils.EMPTY;
        if (processInstance == null) {
            //查询已经结束的流程实例
            HistoricProcessInstance processInstanceHistory =
                    historyService.createHistoricProcessInstanceQuery()
                            .processInstanceId(processInstanceId).singleResult();
            if (processInstanceHistory == null)
                return null;
            else
                processDefinitionId = processInstanceHistory.getProcessDefinitionId();
        } else {
            processDefinitionId = processInstance.getProcessDefinitionId();
        }

        //使用宋体
        String fontName = "宋体";
        //获取BPMN模型对象
        BpmnModel model = repositoryService.getBpmnModel(processDefinitionId);
        //获取流程实例当前的节点，需要高亮显示
        List<String> currentActs = Collections.EMPTY_LIST;
        if (processInstance != null)
            currentActs = runtimeService.getActiveActivityIds(processInstance.getId());

        return processEngine.getProcessEngineConfiguration()
                .getProcessDiagramGenerator()
                .generateDiagram(model, "png", currentActs, new ArrayList<String>(),
                        fontName, fontName, fontName, null, 1.0);
    }


    /**
     * 获取流程图像，已执行节点和流程线高亮显示
     */
    public void getActivitiProccessImage(String pProcessInstanceId, HttpServletResponse response) {
        //logger.info("[开始]-获取流程图图像");
        try {
            //  获取历史流程实例
            HistoricProcessInstance historicProcessInstance = historyService.createHistoricProcessInstanceQuery()
                    .processInstanceId(pProcessInstanceId).singleResult();

            if (historicProcessInstance == null) {
                //throw new BusinessException("获取流程实例ID[" + pProcessInstanceId + "]对应的历史流程实例失败！");
            }
            else {
                // 获取流程定义
                ProcessDefinitionEntity processDefinition = (ProcessDefinitionEntity) ((RepositoryServiceImpl) repositoryService)
                        .getDeployedProcessDefinition(historicProcessInstance.getProcessDefinitionId());

                // 获取流程历史中已执行节点，并按照节点在流程中执行先后顺序排序
                List<HistoricActivityInstance> historicActivityInstanceList = historyService.createHistoricActivityInstanceQuery()
                        .processInstanceId(pProcessInstanceId).orderByHistoricActivityInstanceId().asc().list();

                // 已执行的节点ID集合
                List<String> executedActivityIdList = new ArrayList<String>();
                int index = 1;
                //logger.info("获取已经执行的节点ID");
                for (HistoricActivityInstance activityInstance : historicActivityInstanceList) {
                    executedActivityIdList.add(activityInstance.getActivityId());

                    //logger.info("第[" + index + "]个已执行节点=" + activityInstance.getActivityId() + " : " +activityInstance.getActivityName());
                    index++;
                }

                BpmnModel bpmnModel = repositoryService.getBpmnModel(historicProcessInstance.getProcessDefinitionId());

                // 已执行的线集合
                List<String> flowIds = new ArrayList<String>();
                // 获取流程走过的线 (getHighLightedFlows是下面的方法)
                flowIds = getHighLightedFlows(bpmnModel,processDefinition, historicActivityInstanceList);

//                // 获取流程图图像字符流
//                ProcessDiagramGenerator pec = processEngine.getProcessEngineConfiguration().getProcessDiagramGenerator();
//                //配置字体
//                InputStream imageStream = pec.generateDiagram(bpmnModel, "png", executedActivityIdList, flowIds,"宋体","微软雅黑","黑体",null,2.0);

                Set<String> currIds = runtimeService.createExecutionQuery().processInstanceId(pProcessInstanceId).list()
                        .stream().map(e->e.getActivityId()).collect(Collectors.toSet());

                ICustomProcessDiagramGenerator diagramGenerator = (ICustomProcessDiagramGenerator) processEngine.getProcessEngineConfiguration().getProcessDiagramGenerator();
                InputStream imageStream = diagramGenerator.generateDiagram(bpmnModel, "png", executedActivityIdList,
                        flowIds, "宋体", "宋体", "宋体", null, 1.0, new Color[] { WorkflowConstants.COLOR_NORMAL, WorkflowConstants.COLOR_CURRENT }, currIds);

                response.setContentType("image/png");
                OutputStream os = response.getOutputStream();
                int bytesRead = 0;
                byte[] buffer = new byte[8192];
                while ((bytesRead = imageStream.read(buffer, 0, 8192)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
                os.close();
                imageStream.close();
            }
            //logger.info("[完成]-获取流程图图像");
        } catch (Exception e) {
            System.out.println(e.getMessage());
            //logger.error("【异常】-获取流程图失败！" + e.getMessage());
            //throw new BusinessException("获取流程图失败！" + e.getMessage());
        }
    }

    private List<String> getHighLightedFlows(BpmnModel bpmnModel, ProcessDefinitionEntity processDefinitionEntity, List<HistoricActivityInstance> historicActivityInstances) {
        // 高亮流程已发生流转的线id集合
        List<String> highLightedFlowIds = new ArrayList<>();
        // 全部活动节点
        List<FlowNode> historicActivityNodes = new ArrayList<>();
        // 已完成的历史活动节点
        List<HistoricActivityInstance> finishedActivityInstances = new ArrayList<>();

        for (HistoricActivityInstance historicActivityInstance : historicActivityInstances) {
            FlowNode flowNode = (FlowNode) bpmnModel.getMainProcess().getFlowElement(historicActivityInstance.getActivityId(), true);
            historicActivityNodes.add(flowNode);
            if (historicActivityInstance.getEndTime() != null) {
                finishedActivityInstances.add(historicActivityInstance);
            }
        }

        FlowNode currentFlowNode = null;
        FlowNode targetFlowNode = null;
        // 遍历已完成的活动实例，从每个实例的outgoingFlows中找到已执行的
        for (HistoricActivityInstance currentActivityInstance : finishedActivityInstances) {
            // 获得当前活动对应的节点信息及outgoingFlows信息
            currentFlowNode = (FlowNode) bpmnModel.getMainProcess().getFlowElement(currentActivityInstance.getActivityId(), true);
            List<SequenceFlow> sequenceFlows = currentFlowNode.getOutgoingFlows();

            /**
             * 遍历outgoingFlows并找到已已流转的 满足如下条件认为已已流转： 1.当前节点是并行网关或兼容网关，则通过outgoingFlows能够在历史活动中找到的全部节点均为已流转 2.当前节点是以上两种类型之外的，通过outgoingFlows查找到的时间最早的流转节点视为有效流转
             */
            if ("parallelGateway".equals(currentActivityInstance.getActivityType()) || "inclusiveGateway".equals(currentActivityInstance.getActivityType())) {
                // 遍历历史活动节点，找到匹配流程目标节点的
                for (SequenceFlow sequenceFlow : sequenceFlows) {
                    targetFlowNode = (FlowNode) bpmnModel.getMainProcess().getFlowElement(sequenceFlow.getTargetRef(), true);
                    if (historicActivityNodes.contains(targetFlowNode)) {
                        highLightedFlowIds.add(targetFlowNode.getId());
                    }
                }
            } else {
                List<Map<String, Object>> tempMapList = new ArrayList<>();
                for (SequenceFlow sequenceFlow : sequenceFlows) {
                    for (HistoricActivityInstance historicActivityInstance : historicActivityInstances) {
                        if (historicActivityInstance.getActivityId().equals(sequenceFlow.getTargetRef())) {
                            Map<String, Object> map = new HashMap<>();
                            map.put("highLightedFlowId", sequenceFlow.getId());
                            map.put("highLightedFlowStartTime", historicActivityInstance.getStartTime().getTime());
                            tempMapList.add(map);
                        }
                    }
                }

                if (!CollectionUtils.isEmpty(tempMapList)) {
                    // 遍历匹配的集合，取得开始时间最早的一个
                    long earliestStamp = 0L;
                    String highLightedFlowId = null;
                    for (Map<String, Object> map : tempMapList) {
                        long highLightedFlowStartTime = Long.valueOf(map.get("highLightedFlowStartTime").toString());
                        if (earliestStamp == 0 || earliestStamp >= highLightedFlowStartTime) {
                            highLightedFlowId = map.get("highLightedFlowId").toString();
                            earliestStamp = highLightedFlowStartTime;
                        }
                    }

                    highLightedFlowIds.add(highLightedFlowId);
                }

            }

        }
        return highLightedFlowIds;
    }

    @Override
    public Page taskList(String processDefinitionKey, int pageNum,int pageSize) {
        Page page = new Page(pageNum,pageSize);

        String currentUser = workFlowUtil.getCurrentUsername();
        log.info(workFlowUtil.getCurrentUserAuthority().toString());
        log.info(workFlowUtil.getCurrentTokenValue().toString());


        UserQueryImpl user = new UserQueryImpl();
        user = (UserQueryImpl)identityService.createUserQuery().userId(workFlowUtil.getCurrentUsername());

        List<Task> tasks = new ArrayList<Task>();
        TaskQuery taskQuery = taskService.createTaskQuery();

        if (!StringUtils.equals(processDefinitionKey, "all")) {
            tasks = taskQuery.processDefinitionKey(processDefinitionKey)
                    .taskCandidateOrAssigned(user.getId()).active().orderByTaskId().desc()
                    .listPage(page.getFirstResult(),page.getMaxResults());
        } else {
            tasks = taskQuery.taskCandidateOrAssigned(user.getId()).active().orderByTaskId().desc()
                    .listPage(page.getFirstResult(),page.getMaxResults());
        }
        //获取总页数
        int total = (int) taskQuery.count();
        page.setTotal(total);
        page.setList(tasks);
        return page;
    }

    @Override
    public void claim(String taskId, HttpServletRequest request) {
        //TODO 此处应该添加获取当前操作人的代码,先写死用 leaderuser。
        UserQueryImpl user = new UserQueryImpl();
        user = (UserQueryImpl)identityService.createUserQuery().userId(workFlowUtil.getCurrentUsername());

        taskService.claim(taskId, user.getId());
    }



    @Override
    public void completeTask(String taskId, String processInstanceId,String comment,String type,HttpServletRequest request) {
        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();

        Map<String, Object> formProperties = new HashMap<String, Object>();
        // 从request中读取参数然后转换
        Map<String, String[]> parameterMap = request.getParameterMap();
        Set<Map.Entry<String, String[]>> entrySet = parameterMap.entrySet();
        for (Map.Entry<String, String[]> entry : entrySet) {
            String key = entry.getKey();
            // fp_的意思是form paremeter
            if (StringUtils.defaultString(key).startsWith("fp_")) {
                formProperties.put(key.split("_")[1], entry.getValue()[0]);
            }
        }
        log.debug("start form parameters: {}", formProperties);
        //TODO 此处应该添加获取当前操作人的代码,先写死用 leaderuser。
        UserQueryImpl user = new UserQueryImpl();
        user = (UserQueryImpl)identityService.createUserQuery().userId(workFlowUtil.getCurrentUsername());

        // 用户未登录不能操作，实际应用使用权限框架实现，例如Spring Security、Shiro等
        if (user == null || StringUtils.isBlank(user.getId())) {
            throw new CommonException("用户未登录不能操作");
        }
        try {
            identityService.setAuthenticatedUserId(user.getId());
            //添加评论
            if (!comment.isEmpty()){
                if (type == null){
                    taskService.addComment(taskId,processInstanceId,comment);
                }else{
                    taskService.addComment(taskId,processInstanceId,type,comment);
                }
            }
            //任务委托处理
            DelegationState delegationState = task.getDelegationState();
            if (delegationState != null && "PENDING".equals(delegationState.toString())){
                taskService.resolveTask(taskId,formProperties);
            }else{
                taskService.complete(taskId, formProperties);
            }
        } finally {
            identityService.setAuthenticatedUserId(null);
        }
    }



    /**
     * 获取动态表单提交的数据，并发起流程，表单数据 key-value结构，key需要以fp_开头
     * @param processDefinitionId
     * @param request
     * @return
     */
    @Override
    @Transactional
    public ProcessInstance submitStartFormAndStartProcessInstance(String processDefinitionId,HttpServletRequest request) throws CommonException {
        Map<String, String> formProperties = new HashMap<String, String>();
        // 从request中读取参数然后转换
        Map<String, String[]> parameterMap = request.getParameterMap();
        if (parameterMap.size()>0){
            Set<Map.Entry<String, String[]>> entrySet = parameterMap.entrySet();
            for (Map.Entry<String, String[]> entry : entrySet) {
                String key = entry.getKey();
                // fp_的意思是form paremeter
                if (StringUtils.defaultString(key).startsWith("fp_")) {
                    formProperties.put(key.split("_")[1], entry.getValue()[0]);
                }
            }
        }/*else {
            throw new CommonException("表单数据为空");
        }*/

        log.debug("start form parameters: {}", formProperties);

        //TODO 此处应该添加获取当前操作人的代码,先写死用kafeitu发起流程。用户未登录不能操作，实际应用使用权限框架实现，例如Spring Security、Shiro等
        UserQueryImpl user = new UserQueryImpl();
        user = (UserQueryImpl)identityService.createUserQuery().userId(workFlowUtil.getCurrentUsername());

        ProcessInstance processInstance = null;
        try {
            identityService.setAuthenticatedUserId(user.getId());
            processInstance = formService.submitStartFormData(processDefinitionId, formProperties);
            log.debug("start a processinstance: {}", processInstance);
        } finally {
            identityService.setAuthenticatedUserId(null);
        }
        return processInstance;
    }

    /**
     * 消息启动流程
     * @param messageName
     * @param request
     * @return
     * @throws CommonException
     */
    @Override
    public ProcessInstance messageStartEventInstance(String messageName, HttpServletRequest request) throws CommonException {
        Map<String, Object> formProperties = new HashMap<String, Object>();
        // 从request中读取参数然后转换
        Map<String, String[]> parameterMap = request.getParameterMap();
        if (parameterMap.size()>0){
            Set<Map.Entry<String, String[]>> entrySet = parameterMap.entrySet();
            for (Map.Entry<String, String[]> entry : entrySet) {
                String key = entry.getKey();
                // fp_的意思是form paremeter
                if (StringUtils.defaultString(key).startsWith("fp_")) {
                    formProperties.put(key.split("_")[1], entry.getValue()[0]);
                }
            }
        }

        log.debug("start form parameters: {}", formProperties);

        UserQueryImpl user = new UserQueryImpl();
        user = (UserQueryImpl)identityService.createUserQuery().userId(workFlowUtil.getCurrentUsername());

        ProcessInstance processInstance = null;
        try {
            identityService.setAuthenticatedUserId(user.getId());
            processInstance = runtimeService.startProcessInstanceByMessage(messageName,formProperties);

            log.debug("start a processinstance: {}", processInstance);
        } finally {
            identityService.setAuthenticatedUserId(null);
        }
        return processInstance;
    }

    /**
     * 信号触发
     * @param signalName
     * @param executionId
     * @param request
     * @throws CommonException
     */
    @Override
    public void signalStartEventInstance(String signalName,  String executionId ,HttpServletRequest request)  {
        Map<String, Object> formProperties = new HashMap<String, Object>();
        // 从request中读取参数然后转换
        Map<String, String[]> parameterMap = request.getParameterMap();
        if (parameterMap.size()>0){
            Set<Map.Entry<String, String[]>> entrySet = parameterMap.entrySet();
            for (Map.Entry<String, String[]> entry : entrySet) {
                String key = entry.getKey();
                // fp_的意思是form paremeter
                if (StringUtils.defaultString(key).startsWith("fp_")) {
                    formProperties.put(key.split("_")[1], entry.getValue()[0]);
                }
            }
        }

        log.debug("start form parameters: {}", formProperties);

        UserQueryImpl user = new UserQueryImpl();
        user = (UserQueryImpl)identityService.createUserQuery().userId(workFlowUtil.getCurrentUsername());

        if (!executionId.isEmpty() && formProperties.size() > 0){
            runtimeService.signalEventReceived(signalName,executionId,formProperties);
        }else if(!executionId.isEmpty()){
            runtimeService.signalEventReceived(signalName,executionId);
        }else{
            runtimeService.signalEventReceived(signalName);
        }
    }

    /**
     * 消息触发
     * @param messageName
     * @param executionId
     * @param request
     */
    @Override
    public void messageEventReceived(String messageName, String executionId, HttpServletRequest request) {
        Map<String, Object> formProperties = new HashMap<String, Object>();
        // 从request中读取参数然后转换
        Map<String, String[]> parameterMap = request.getParameterMap();
        if (parameterMap.size()>0){
            Set<Map.Entry<String, String[]>> entrySet = parameterMap.entrySet();
            for (Map.Entry<String, String[]> entry : entrySet) {
                String key = entry.getKey();
                // fp_的意思是form paremeter
                if (StringUtils.defaultString(key).startsWith("fp_")) {
                    formProperties.put(key.split("_")[1], entry.getValue()[0]);
                }
            }
        }

        log.debug("start form parameters: {}", formProperties);

        UserQueryImpl user = new UserQueryImpl();
        user = (UserQueryImpl)identityService.createUserQuery().userId(workFlowUtil.getCurrentUsername());

        if (!executionId.isEmpty() && formProperties.size() > 0){
            runtimeService.messageEventReceived(messageName,executionId,formProperties);
        }else if(!executionId.isEmpty()){
            runtimeService.messageEventReceived(messageName,executionId);
        }
    }

    /**
     * 获取信号事件的执行列表
     * @param pageNum
     * @param pageSize
     * @param signalName
     * @param processInstanceId
     * @return
     * @throws CommonException
     */
    @Override
    public Page signalEventSubscriptionName(int pageNum, int pageSize, String signalName,String processInstanceId)  {
        Page page = new Page(pageNum,pageSize);

        UserQueryImpl user = new UserQueryImpl();
        user = (UserQueryImpl)identityService.createUserQuery().userId(workFlowUtil.getCurrentUsername());

        List<AcExecutionEntityImpl> Acexecutions = new ArrayList<AcExecutionEntityImpl>();

        ExecutionQuery executionQuery = runtimeService.createExecutionQuery();
        if (!processInstanceId.isEmpty()){
            executionQuery = executionQuery.processInstanceId(processInstanceId);
        }

        List<Execution> executions = executionQuery
                .signalEventSubscriptionName(signalName)
                .listPage(page.getFirstResult(),page.getMaxResults());

        for (int i = 0; i <executions.size(); i++) {
            ExecutionEntityImpl execution = (ExecutionEntityImpl) executions.get(i);
            AcExecutionEntityImpl acExecutionEntity = new AcExecutionEntityImpl();

            acExecutionEntity.setId(execution.getId());
            acExecutionEntity.setActivitiId(execution.getActivityId());
            acExecutionEntity.setActivitiName(execution.getActivityName());
            acExecutionEntity.setParentId(execution.getParentId());
            acExecutionEntity.setProcessDefinitionId(execution.getProcessDefinitionId());
            acExecutionEntity.setProcessDefinitionKey(execution.getProcessDefinitionKey());
            acExecutionEntity.setRootProcessInstanceId(execution.getRootProcessInstanceId());
            acExecutionEntity.setProcessInstanceId(execution.getProcessInstanceId());
            Acexecutions.add(acExecutionEntity);
        }

        //获取总页数
        int total = (int) executionQuery.count();
        page.setTotal(total);
        page.setList(Acexecutions);
        return page;
    }

    /**
     * 获取消息事件的执行列表
     * @param pageNum
     * @param pageSize
     * @param messageName
     * @param processInstanceId
     * @return
     */
    @Override
    public Page messageEventSubscriptionName(int pageNum, int pageSize, String messageName, String processInstanceId) {
        Page page = new Page(pageNum,pageSize);

        UserQueryImpl user = new UserQueryImpl();
        user = (UserQueryImpl)identityService.createUserQuery().userId(workFlowUtil.getCurrentUsername());

        List<AcExecutionEntityImpl> Acexecutions = new ArrayList<AcExecutionEntityImpl>();

        ExecutionQuery executionQuery = runtimeService.createExecutionQuery();
        if (!processInstanceId.isEmpty()){
            executionQuery = executionQuery.processInstanceId(processInstanceId);
        }

        List<Execution> executions = executionQuery
                .messageEventSubscriptionName(messageName)
                .listPage(page.getFirstResult(),page.getMaxResults());

        for (int i = 0; i <executions.size(); i++) {
            ExecutionEntityImpl execution = (ExecutionEntityImpl) executions.get(i);
            AcExecutionEntityImpl acExecutionEntity = new AcExecutionEntityImpl();

            acExecutionEntity.setId(execution.getId());
            acExecutionEntity.setActivitiId(execution.getActivityId());
            acExecutionEntity.setActivitiName(execution.getActivityName());
            acExecutionEntity.setParentId(execution.getParentId());
            acExecutionEntity.setProcessDefinitionId(execution.getProcessDefinitionId());
            acExecutionEntity.setProcessDefinitionKey(execution.getProcessDefinitionKey());
            acExecutionEntity.setRootProcessInstanceId(execution.getRootProcessInstanceId());
            acExecutionEntity.setProcessInstanceId(execution.getProcessInstanceId());
            Acexecutions.add(acExecutionEntity);
        }

        //获取总页数
        int total = (int) executionQuery.count();
        page.setTotal(total);
        page.setList(Acexecutions);
        return page;
    }

    /**
     * 删除流程实例
     * @param processInstanceId
     * @param deleteReason
     */
    @Override
    public void deleteProcessInstance(String processInstanceId, String deleteReason) {
        runtimeService.deleteProcessInstance(processInstanceId,deleteReason);
    }

    /**
     * 驳回指定节点
     * @param taskId
     * @param flowElementId
     */
    @Override
    @Transactional(noRollbackFor = Exception.class)
    public void rejectAnyNode(String taskId, String flowElementId) {

        FlowNode targetNode = null;
        log.info("流程驳回开始>>>>>>>>>>>>>>>>>>>>");
        //获取当前任务
        Task currentTask = taskService.createTaskQuery().taskId(taskId).singleResult();

        //获取当前操作人
        UserQueryImpl user = new UserQueryImpl();
        user = (UserQueryImpl)identityService.createUserQuery().userId(workFlowUtil.getCurrentUsername());

        //判断当前用户是否为该节点处理人
        if(currentTask.getAssignee() == null || !currentTask.getAssignee().equals(user.getId())){
            throw new ActivitiException("当前用户无法驳回,请先签收任务");
        }

        //获取当前节点信息
        String currActivityId = currentTask.getTaskDefinitionKey();
        String processDefinitionId = currentTask.getProcessDefinitionId();
        BpmnModel bpmnModel = repositoryService.getBpmnModel(processDefinitionId);
        FlowNode currFlow = (FlowNode) bpmnModel.getMainProcess().getFlowElement(currActivityId);
        if (null == currFlow) {
            List<SubProcess> subProcessList = bpmnModel.getMainProcess().findFlowElementsOfType(SubProcess.class, true);
            for (SubProcess subProcess : subProcessList) {
                FlowElement flowElement = subProcess.getFlowElement(currActivityId);
                if (flowElement != null) {
                    currFlow = (FlowNode) flowElement;
                    break;
                }
            }
        }

        //获取流程定义
        Process process = repositoryService.getBpmnModel(currentTask.getProcessDefinitionId()).getMainProcess();
        log.info("流程驳回>>>>>>>,流程名称:{}:{},当前任务:{}:{}",process.getName(),process.getId(),currentTask.getName(),currentTask.getId());
        if (flowElementId == null || flowElementId.isEmpty()){
            //获取流程定义第一个Node节点
            targetNode = findFirstActivityNode(currentTask.getProcessDefinitionId());
        }else{
            //获取目标节点定义
            targetNode = (FlowNode)process.getFlowElement(flowElementId);
        }
        if (targetNode == null){
            throw new ActivitiException("目标节点为空");
        }
        //如果不是同一个流程(子流程)不能驳回
        if (!(currFlow.getParentContainer().equals(targetNode.getParentContainer()))) {
            throw new ActivitiException("不是同一个流程(子流程)不能驳回");
        }
        //删除当前运行任务
        String executionEntityId = managementService.executeCommand(new DeleteTaskCmd(currentTask.getId()));
        //流程执行到来源节点
        managementService.executeCommand(new SetFLowNodeAndGoCmd(targetNode, executionEntityId));
        log.info("流程驳回成功<<<<<<<<<<<<<<<<<<<<<<<");
    }

    //删除当前运行时任务命令，并返回当前任务的执行对象id，这里继承了NeedsActiveTaskCmd，主要时很多跳转业务场景下，要求不能是挂起任务。
    public class DeleteTaskCmd extends NeedsActiveTaskCmd<String> {
        public DeleteTaskCmd(String taskId){
            super(taskId);
        }
        public String execute(CommandContext commandContext, TaskEntity currentTask){
            //获取所需服务
            TaskEntityManagerImpl taskEntityManager = (TaskEntityManagerImpl)commandContext.getTaskEntityManager();
            //获取当前任务的来源任务及来源节点信息
            ExecutionEntity executionEntity = currentTask.getExecution();
            //删除当前任务,来源任务
            taskEntityManager.deleteTask(currentTask, "流程驳回", false, false);
            return executionEntity.getId();
        }
        public String getSuspendedTaskException() {
            return "挂起的任务不能跳转";
        }
    }

    //根据提供节点和执行对象id，进行跳转命令
    public class SetFLowNodeAndGoCmd implements Command<Void> {
        private FlowNode flowElement;
        private String executionId;
        public SetFLowNodeAndGoCmd(FlowNode flowElement,String executionId){
            this.flowElement = flowElement;
            this.executionId = executionId;
        }

        public Void execute(CommandContext commandContext){
            //获取目标节点的来源连线
            List<SequenceFlow> flows = flowElement.getIncomingFlows();
            if(flows==null || flows.size()<1){
                throw new ActivitiException("回退错误，目标节点没有来源连线");
            }
            //随便选一条连线来执行，当前执行计划为，从连线流转到目标节点，实现跳转
            ExecutionEntity executionEntity = commandContext.getExecutionEntityManager().findById(executionId);
            executionEntity.setCurrentFlowElement(flows.get(0));
            commandContext.getAgenda().planTakeOutgoingSequenceFlowsOperation(executionEntity, true);
            return null;
        }
    }

    /**
     * 获得流程定义第一个节点
     */
    public FlowNode findFirstActivityNode(String processDefinitionId) {
        BpmnModel model = repositoryService.getBpmnModel(processDefinitionId);
        //Process process = ProcessDefinitionUtil.getProcess(processDefinitionId);
        Process process = model.getMainProcess();
        FlowElement flowElement = process.getInitialFlowElement();
        FlowNode startActivity = (FlowNode) flowElement;

        if (startActivity.getOutgoingFlows().size() != 1) {
            throw new IllegalStateException(
                    "start activity outgoing transitions cannot more than 1, now is : "
                            + startActivity.getOutgoingFlows().size());
        }

        SequenceFlow sequenceFlow = startActivity.getOutgoingFlows()
                .get(0);
        FlowNode targetActivity = (FlowNode) sequenceFlow.getTargetFlowElement();

        if (!(targetActivity instanceof UserTask)) {
            log.info("first activity is not userTask, just skip");
            return null;
        }

        return targetActivity;
    }

    /**
     * 任务委托
     * @param taskId
     * @param userId
     */
    @Override
    public void delegateTask(String taskId, String userId) {
        taskService.delegateTask(taskId, userId);
        //添加备注
        Task currentTask = taskService.createTaskQuery().taskId(taskId).singleResult();
        taskService.addComment(taskId,currentTask.getProcessInstanceId(),"任务委托给"+userId);
    }

    /**
     * 挂起、激活流程实例
     * @param processInstanceId
     * @param suspendState
     */
    @Override
    public void suspendProcessInstance(String processInstanceId, String suspendState) {
        if ("2".equals(suspendState)) {
            runtimeService.suspendProcessInstanceById(processInstanceId);
        } else if ("1".equals(suspendState)) {
            runtimeService.activateProcessInstanceById(processInstanceId);
        }
    }

}
