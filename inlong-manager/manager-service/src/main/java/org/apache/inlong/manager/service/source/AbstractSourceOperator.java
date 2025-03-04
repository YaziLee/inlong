/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.inlong.manager.service.source;

import org.apache.inlong.common.enums.DataTypeEnum;
import org.apache.inlong.manager.common.consts.InlongConstants;
import org.apache.inlong.manager.common.consts.SourceType;
import org.apache.inlong.manager.common.enums.ErrorCodeEnum;
import org.apache.inlong.manager.common.enums.GroupStatus;
import org.apache.inlong.manager.common.enums.SourceStatus;
import org.apache.inlong.manager.common.exceptions.BusinessException;
import org.apache.inlong.manager.common.util.CommonBeanUtils;
import org.apache.inlong.manager.dao.entity.InlongStreamFieldEntity;
import org.apache.inlong.manager.dao.entity.StreamSourceEntity;
import org.apache.inlong.manager.dao.entity.StreamSourceFieldEntity;
import org.apache.inlong.manager.dao.mapper.InlongStreamFieldEntityMapper;
import org.apache.inlong.manager.dao.mapper.StreamSourceEntityMapper;
import org.apache.inlong.manager.dao.mapper.StreamSourceFieldEntityMapper;
import org.apache.inlong.manager.pojo.common.PageResult;
import org.apache.inlong.manager.pojo.source.SourceRequest;
import org.apache.inlong.manager.pojo.source.StreamSource;
import org.apache.inlong.manager.pojo.stream.StreamField;
import org.apache.inlong.manager.service.node.DataNodeService;

import com.github.pagehelper.Page;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Default operator of stream source.
 */
public abstract class AbstractSourceOperator implements StreamSourceOperator {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractSourceOperator.class);

    @Autowired
    protected StreamSourceEntityMapper sourceMapper;
    @Autowired
    protected StreamSourceFieldEntityMapper sourceFieldMapper;
    @Autowired
    protected InlongStreamFieldEntityMapper streamFieldMapper;
    @Autowired
    protected DataNodeService dataNodeService;

    /**
     * Getting the source type.
     *
     * @return source type string.
     */
    protected abstract String getSourceType();

    /**
     * Setting the parameters of the latest entity.
     *
     * @param request source request
     * @param targetEntity entity object which will set the new parameters.
     */
    protected abstract void setTargetEntity(SourceRequest request, StreamSourceEntity targetEntity);

    @Override
    public String getExtParams(StreamSourceEntity sourceEntity) {
        return sourceEntity.getExtParams();
    }

    @Override
    @Transactional(rollbackFor = Throwable.class)
    public Integer saveOpt(SourceRequest request, Integer groupStatus, String operator) {
        StreamSourceEntity entity = CommonBeanUtils.copyProperties(request, StreamSourceEntity::new);
        if (SourceType.AUTO_PUSH.equals(request.getSourceType())) {
            // auto push task needs not be issued to agent
            entity.setStatus(SourceStatus.SOURCE_NORMAL.getCode());
        } else if (GroupStatus.forCode(groupStatus).equals(GroupStatus.CONFIG_SUCCESSFUL)) {
            entity.setStatus(SourceStatus.TO_BE_ISSUED_ADD.getCode());
        } else {
            entity.setStatus(SourceStatus.SOURCE_NEW.getCode());
        }
        entity.setCreator(operator);
        entity.setModifier(operator);

        // get the ext params
        setTargetEntity(request, entity);
        sourceMapper.insert(entity);
        saveFieldOpt(entity, request.getFieldList());
        if (request.getEnableSyncSchema()) {
            syncSourceFieldInfo(request, operator);
        }
        return entity.getId();
    }

    @Override
    public List<StreamField> getSourceFields(Integer sourceId) {
        List<StreamSourceFieldEntity> sourceFieldEntities = sourceFieldMapper.selectBySourceId(sourceId);
        return CommonBeanUtils.copyListProperties(sourceFieldEntities, StreamField::new);
    }

    @Override
    public PageResult<? extends StreamSource> getPageInfo(Page<StreamSourceEntity> entityPage) {
        if (CollectionUtils.isEmpty(entityPage)) {
            return PageResult.empty();
        }
        return PageResult.fromPage(entityPage).map(this::getFromEntity);
    }

    @Override
    @Transactional(rollbackFor = Throwable.class, isolation = Isolation.REPEATABLE_READ)
    public void updateOpt(SourceRequest request, Integer groupStatus, Integer groupMode, String operator) {
        StreamSourceEntity entity = sourceMapper.selectByIdForUpdate(request.getId());
        if (entity == null) {
            throw new BusinessException(ErrorCodeEnum.SOURCE_INFO_NOT_FOUND,
                    String.format("not found source record by id=%d", request.getId()));
        }
        if (SourceType.AUTO_PUSH.equals(entity.getSourceType())) {
            updateFieldOpt(entity, request.getFieldList());
            return;
        }
        boolean allowUpdate = InlongConstants.DATASYNC_MODE.equals(groupMode)
                || SourceStatus.ALLOWED_UPDATE.contains(entity.getStatus());
        if (!allowUpdate) {
            throw new BusinessException(ErrorCodeEnum.SOURCE_OPT_NOT_ALLOWED,
                    String.format(
                            "source=%s is not allowed to update, please wait until its changed to final status or stop / frozen / delete it firstly",
                            entity));
        }
        String errMsg = String.format("source has already updated with groupId=%s, streamId=%s, name=%s, curVersion=%s",
                request.getInlongGroupId(), request.getInlongStreamId(), request.getSourceName(), request.getVersion());
        if (!Objects.equals(entity.getVersion(), request.getVersion())) {
            throw new BusinessException(ErrorCodeEnum.CONFIG_EXPIRED, errMsg);
        }

        // source type cannot be changed
        if (!Objects.equals(entity.getSourceType(), request.getSourceType())) {
            throw new BusinessException(ErrorCodeEnum.INVALID_PARAMETER,
                    String.format("source type=%s cannot change to %s", entity.getSourceType(),
                            request.getSourceType()));
        }

        String groupId = request.getInlongGroupId();
        String streamId = request.getInlongStreamId();
        String sourceName = request.getSourceName();
        List<StreamSourceEntity> sourceList = sourceMapper.selectByRelatedId(groupId, streamId, sourceName);
        for (StreamSourceEntity sourceEntity : sourceList) {
            Integer sourceId = sourceEntity.getId();
            if (!Objects.equals(sourceId, request.getId())) {
                throw new BusinessException(ErrorCodeEnum.SOURCE_ALREADY_EXISTS,
                        String.format("source name=%s already exists with the groupId=%s streamId=%s", sourceName,
                                groupId, streamId));
            }
        }

        // setting updated parameters of stream source entity.
        setTargetEntity(request, entity);
        entity.setModifier(operator);
        entity.setPreviousStatus(entity.getStatus());

        // re-issue task if necessary
        if (InlongConstants.STANDARD_MODE.equals(groupMode)) {
            SourceStatus sourceStatus = SourceStatus.forCode(entity.getStatus());
            Integer nextStatus = entity.getStatus();
            if (GroupStatus.forCode(groupStatus).equals(GroupStatus.CONFIG_SUCCESSFUL)) {
                nextStatus = SourceStatus.TO_BE_ISSUED_RETRY.getCode();
            } else {
                switch (SourceStatus.forCode(entity.getStatus())) {
                    case SOURCE_NORMAL:
                    case HEARTBEAT_TIMEOUT:
                        nextStatus = SourceStatus.TO_BE_ISSUED_RETRY.getCode();
                        break;
                    case SOURCE_FAILED:
                        nextStatus = SourceStatus.SOURCE_NEW.getCode();
                        break;
                    default:
                        // others leave it be
                        break;
                }
            }
            entity.setStatus(nextStatus);
        }

        int rowCount = sourceMapper.updateByPrimaryKeySelective(entity);
        if (rowCount != InlongConstants.AFFECTED_ONE_ROW) {
            throw new BusinessException(ErrorCodeEnum.CONFIG_EXPIRED, errMsg);
        }
        updateFieldOpt(entity, request.getFieldList());
        LOGGER.debug("success to update source of type={}", request.getSourceType());
    }

    @Override
    @Transactional(rollbackFor = Throwable.class, isolation = Isolation.REPEATABLE_READ)
    public void stopOpt(SourceRequest request, String operator) {
        StreamSourceEntity existEntity = sourceMapper.selectByIdForUpdate(request.getId());
        SourceStatus curState = SourceStatus.forCode(existEntity.getStatus());
        SourceStatus nextState = SourceStatus.TO_BE_ISSUED_STOP;
        if (curState == SourceStatus.SOURCE_STOP) {
            return;
        }
        if (!SourceStatus.isAllowedTransition(curState, nextState)) {
            throw new BusinessException(String.format("current source status=%s for id=%s is not allowed to stop",
                    existEntity.getStatus(), existEntity.getId()));
        }
        StreamSourceEntity curEntity = CommonBeanUtils.copyProperties(request, StreamSourceEntity::new);
        curEntity.setPreviousStatus(curState.getCode());
        curEntity.setStatus(nextState.getCode());
        int rowCount = sourceMapper.updateByPrimaryKeySelective(curEntity);
        if (rowCount != InlongConstants.AFFECTED_ONE_ROW) {
            LOGGER.error("source has already updated with groupId={}, streamId={}, name={}, curVersion={}",
                    curEntity.getInlongGroupId(), curEntity.getInlongStreamId(), curEntity.getSourceName(),
                    curEntity.getVersion());
            throw new BusinessException(ErrorCodeEnum.CONFIG_EXPIRED);
        }
    }

    @Override
    @Transactional(rollbackFor = Throwable.class, isolation = Isolation.REPEATABLE_READ)
    public void restartOpt(SourceRequest request, String operator) {
        StreamSourceEntity existEntity = sourceMapper.selectByIdForUpdate(request.getId());
        SourceStatus curState = SourceStatus.forCode(existEntity.getStatus());
        SourceStatus nextState = SourceStatus.TO_BE_ISSUED_ACTIVE;
        if (!SourceStatus.isAllowedTransition(curState, nextState)) {
            throw new BusinessException(String.format("current source status=%s for id=%s is not allowed to restart",
                    existEntity.getStatus(), existEntity.getId()));
        }
        StreamSourceEntity curEntity = CommonBeanUtils.copyProperties(request, StreamSourceEntity::new);
        curEntity.setPreviousStatus(curState.getCode());
        curEntity.setStatus(nextState.getCode());
        int rowCount = sourceMapper.updateByPrimaryKeySelective(curEntity);
        if (rowCount != InlongConstants.AFFECTED_ONE_ROW) {
            LOGGER.error("source has already updated with groupId={}, streamId={}, name={}, curVersion={}",
                    curEntity.getInlongGroupId(), curEntity.getInlongStreamId(), curEntity.getSourceName(),
                    curEntity.getVersion());
            throw new BusinessException(ErrorCodeEnum.CONFIG_EXPIRED);
        }
    }

    protected void updateFieldOpt(StreamSourceEntity entity, List<StreamField> fieldInfos) {
        Integer sourceId = entity.getId();
        if (CollectionUtils.isEmpty(fieldInfos)) {
            return;
        }

        // Stream source fields
        sourceFieldMapper.deleteAll(sourceId);
        this.saveFieldOpt(entity, fieldInfos);

        // InLong stream fields
        String groupId = entity.getInlongGroupId();
        String streamId = entity.getInlongStreamId();
        streamFieldMapper.deleteAllByIdentifier(groupId, streamId);
        saveStreamField(groupId, streamId, fieldInfos);

        LOGGER.debug("success to update source fields");
    }

    protected void saveStreamField(String groupId, String streamId, List<StreamField> infoList) {
        if (CollectionUtils.isEmpty(infoList)) {
            return;
        }
        infoList.forEach(streamField -> streamField.setId(null));
        List<InlongStreamFieldEntity> list = CommonBeanUtils.copyListProperties(infoList,
                InlongStreamFieldEntity::new);
        for (InlongStreamFieldEntity entity : list) {
            entity.setInlongGroupId(groupId);
            entity.setInlongStreamId(streamId);
            entity.setIsDeleted(InlongConstants.UN_DELETED);
        }
        streamFieldMapper.insertAll(list);
    }

    protected void saveFieldOpt(StreamSourceEntity entity, List<StreamField> fieldInfos) {
        LOGGER.debug("begin to save source fields={}", fieldInfos);
        if (CollectionUtils.isEmpty(fieldInfos)) {
            return;
        }

        int size = fieldInfos.size();
        List<StreamSourceFieldEntity> entityList = new ArrayList<>(size);
        String groupId = entity.getInlongGroupId();
        String streamId = entity.getInlongStreamId();
        String sourceType = entity.getSourceType();
        Integer sourceId = entity.getId();
        for (StreamField fieldInfo : fieldInfos) {
            StreamSourceFieldEntity fieldEntity = CommonBeanUtils.copyProperties(fieldInfo,
                    StreamSourceFieldEntity::new);
            if (StringUtils.isEmpty(fieldEntity.getFieldComment())) {
                fieldEntity.setFieldComment(fieldEntity.getFieldName());
            }
            fieldEntity.setInlongGroupId(groupId);
            fieldEntity.setInlongStreamId(streamId);
            fieldEntity.setSourceId(sourceId);
            fieldEntity.setSourceType(sourceType);
            fieldEntity.setIsDeleted(InlongConstants.UN_DELETED);
            entityList.add(fieldEntity);
        }

        sourceFieldMapper.insertAll(entityList);
        LOGGER.debug("success to save source fields");
    }

    /**
     * If the stream source can only use one data type, return the data type that has been set.
     *
     * @param streamSource stream source
     * @param streamDataType stream data type
     * @return serialization type
     */
    protected String getSerializationType(StreamSource streamSource, String streamDataType) {
        if (StringUtils.isNotBlank(streamSource.getSerializationType())) {
            return streamSource.getSerializationType();
        }

        return DataTypeEnum.forType(streamDataType).getType();
    }

    @Override
    @Transactional(rollbackFor = Throwable.class, isolation = Isolation.REPEATABLE_READ)
    public void syncSourceFieldInfo(SourceRequest request, String operator) {
        LOGGER.info("not support sync source field info for type ={}", request.getSourceType());
    }
}
