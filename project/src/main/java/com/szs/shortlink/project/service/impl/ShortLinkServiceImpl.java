/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.szs.shortlink.project.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.text.StrBuilder;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.szs.shortlink.project.common.convention.exception.ClientException;
import com.szs.shortlink.project.common.convention.exception.ServiceException;
import com.szs.shortlink.project.common.enums.VailDateTypeEnum;
import com.szs.shortlink.project.config.GotoDomainWhiteListConfiguration;
import com.szs.shortlink.project.dao.entity.*;
import com.szs.shortlink.project.dao.mapper.*;
import com.szs.shortlink.project.dto.biz.ShortLinkStatsRecordDTO;
import com.szs.shortlink.project.dto.req.ShortLinkBatchCreateReqDTO;
import com.szs.shortlink.project.dto.req.ShortLinkCreateReqDTO;
import com.szs.shortlink.project.dto.req.ShortLinkPageReqDTO;
import com.szs.shortlink.project.dto.req.ShortLinkUpdateReqDTO;
import com.szs.shortlink.project.dto.resp.*;
import com.szs.shortlink.project.mq.producer.ShortLinkStatsSaveProducer;
import com.szs.shortlink.project.service.LinkStatsTodayService;
import com.szs.shortlink.project.service.ShortLinkService;
import com.szs.shortlink.project.toolkit.HashUtil;
import com.szs.shortlink.project.toolkit.LinkUtil;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RReadWriteLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.constructor.DuplicateKeyException;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.szs.shortlink.project.common.constant.RedisKeyConstant.*;

/**
 * 短链接接口实现层
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShortLinkServiceImpl extends ServiceImpl<ShortLinkMapper, ShortLinkDO> implements ShortLinkService {

    private final RBloomFilter<String> shortUriCreateCachePenetrationBloomFilter;
    private final ShortLinkGotoMapper shortLinkGotoMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private final LinkAccessStatsMapper linkAccessStatsMapper;
    private final LinkLocaleStatsMapper linkLocaleStatsMapper;
    private final LinkOsStatsMapper linkOsStatsMapper;
    private final LinkBrowserStatsMapper linkBrowserStatsMapper;
    private final LinkAccessLogsMapper linkAccessLogsMapper;
    private final LinkDeviceStatsMapper linkDeviceStatsMapper;
    private final LinkNetworkStatsMapper linkNetworkStatsMapper;
    private final LinkStatsTodayMapper linkStatsTodayMapper;
    private final LinkStatsTodayService linkStatsTodayService;
    private final ShortLinkStatsSaveProducer shortLinkStatsSaveProducer;
    private final GotoDomainWhiteListConfiguration gotoDomainWhiteListConfiguration;

    @Value("${short-link.domain.default}")
    private String createShortLinkDefaultDomain;


    @Value("${short-link.stats.locale.amap-key}")
    private String statsLocaleAmapKey;

    /**
     *
     * @param requestParam 创建短链接请求参数
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public ShortLinkCreateRespDTO createShortLink(ShortLinkCreateReqDTO requestParam){
        String shortLinkSuffix = generateSuffix(requestParam);
        String fullShortUrl = StrBuilder.create(createShortLinkDefaultDomain)
                .append("/")
                .append(shortLinkSuffix)
                .toString();
        ShortLinkDO shortLinkDO = ShortLinkDO.builder()
                .domain(createShortLinkDefaultDomain)
                .originUrl(requestParam.getOriginUrl())
                .gid(requestParam.getGid())
                .createdType(requestParam.getCreatedType())
                .validDateType(requestParam.getValidDateType())
                .validDate(requestParam.getValidDate())
                .describe(requestParam.getDescribe())
                .shortUri(shortLinkSuffix)
                .enableStatus(0)
                .totalPv(0)
                .totalUv(0)
                .totalUip(0)
                .delTime(0L)
                .fullShortUrl(fullShortUrl)
                .favicon(getFavicon(requestParam.getOriginUrl()))
                .build();
        ShortLinkGotoDO linkGotoDO = ShortLinkGotoDO.builder()
                .fullShortUrl(fullShortUrl)
                .gid(requestParam.getGid())
                .build();
        try {
            baseMapper.insert(shortLinkDO);
            shortLinkGotoMapper.insert(linkGotoDO);
        }catch(DuplicateKeyException ex){
            LambdaQueryWrapper<ShortLinkDO> queryWrapper = Wrappers.lambdaQuery(ShortLinkDO.class).eq(ShortLinkDO::getFullShortUrl, fullShortUrl);
            ShortLinkDO hasShortLinkDO = baseMapper.selectOne(queryWrapper);
            if(hasShortLinkDO != null){
                log.warn("短链接{}重复入库",fullShortUrl);
                throw new ServiceException("短链接生成重复");
            }
        }
        stringRedisTemplate.opsForValue().set(
            String.format(GOTO_SHORT_LINK_KEY, fullShortUrl),
            requestParam.getOriginUrl(),
            LinkUtil.getLinkCacheValidTime(requestParam.getValidDate()), TimeUnit.MILLISECONDS
        );
        shortUriCreateCachePenetrationBloomFilter.add(fullShortUrl);
        return ShortLinkCreateRespDTO.builder()
                .fullShortUrl("http://" + shortLinkDO.getFullShortUrl())
                .originUrl(requestParam.getOriginUrl())
                .gid(requestParam.getGid())
                .build();

    }


    /**
     * 生成短链接
     * @param requestParam
     * @return
     */
    private String generateSuffix(ShortLinkCreateReqDTO requestParam) {
        int customGenerateCount = 0;
        String shorUri;
        while (true) {
            if (customGenerateCount > 10) {
                throw new ServiceException("短链接频繁生成，请稍后再试");
            }
            String originUrl = requestParam.getOriginUrl();
            originUrl += UUID.randomUUID().toString();
            // 短链接哈希算法生成冲突问题如何解决？详情查看：https://nageoffer.com/shortlink/question
            shorUri = HashUtil.hashToBase62(originUrl);
            // 判断短链接是否存在为什么不使用Set结构？详情查看：https://nageoffer.com/shortlink/question
            // 如果布隆过滤器挂了，里边存的数据全丢失了，怎么恢复呢？详情查看：https://nageoffer.com/shortlink/question
            if (!shortUriCreateCachePenetrationBloomFilter.contains(createShortLinkDefaultDomain+ "/" + shorUri)) {
                break;
            }
            customGenerateCount++;
        }
        return shorUri;
    }


//    @Transactional(rollbackFor = Exception.class)
//    @Override
//    public ShortLinkCreateRespDTO createShortLink(ShortLinkCreateReqDTO requestParam) {
//        // 短链接接口的并发量有多少？如何测试？详情查看：https://nageoffer.com/shortlink/question
//        verificationWhitelist(requestParam.getOriginUrl());
//        String shortLinkSuffix = generateSuffix(requestParam);
//        String fullShortUrl = StrBuilder.create(createShortLinkDefaultDomain)
//                .append("/")
//                .append(shortLinkSuffix)
//                .toString();
//        ShortLinkDO shortLinkDO = ShortLinkDO.builder()
//                .domain(createShortLinkDefaultDomain)
//                .originUrl(requestParam.getOriginUrl())
//                .gid(requestParam.getGid())
//                .createdType(requestParam.getCreatedType())
//                .validDateType(requestParam.getValidDateType())
//                .validDate(requestParam.getValidDate())
//                .describe(requestParam.getDescribe())
//                .shortUri(shortLinkSuffix)
//                .enableStatus(0)
//                .totalPv(0)
//                .totalUv(0)
//                .totalUip(0)
//                .delTime(0L)
//                .fullShortUrl(fullShortUrl)
//                .favicon(getFavicon(requestParam.getOriginUrl()))
//                .build();
//        ShortLinkGotoDO linkGotoDO = ShortLinkGotoDO.builder()
//                .fullShortUrl(fullShortUrl)
//                .gid(requestParam.getGid())
//                .build();
//        try {
//            // 短链接项目有多少数据？如何解决海量数据存储？详情查看：https://nageoffer.com/shortlink/question
//            baseMapper.insert(shortLinkDO);
//            // 短链接数据库分片键是如何考虑的？详情查看：https://nageoffer.com/shortlink/question
//            shortLinkGotoMapper.insert(linkGotoDO);
//        } catch (DuplicateKeyException ex) {
//            // 首先判断是否存在布隆过滤器，如果不存在直接新增
//            if (!shortUriCreateCachePenetrationBloomFilter.contains(fullShortUrl)) {
//                shortUriCreateCachePenetrationBloomFilter.add(fullShortUrl);
//            }
//            throw new ServiceException(String.format("短链接：%s 生成重复", fullShortUrl));
//        }
//        // 项目中短链接缓存预热是怎么做的？详情查看：https://nageoffer.com/shortlink/question
//        stringRedisTemplate.opsForValue().set(
//                String.format(GOTO_SHORT_LINK_KEY, fullShortUrl),
//                requestParam.getOriginUrl(),
//                LinkUtil.getLinkCacheValidTime(requestParam.getValidDate()), TimeUnit.MILLISECONDS
//        );
//        // 删除短链接后，布隆过滤器如何删除？详情查看：https://nageoffer.com/shortlink/question
//        shortUriCreateCachePenetrationBloomFilter.add(fullShortUrl);
//        return ShortLinkCreateRespDTO.builder()
//                .fullShortUrl("http://" + shortLinkDO.getFullShortUrl())
//                .originUrl(requestParam.getOriginUrl())
//                .gid(requestParam.getGid())
//                .build();
//    }
//
//    @Override
//    public ShortLinkCreateRespDTO createShortLinkByLock(ShortLinkCreateReqDTO requestParam) {
//        verificationWhitelist(requestParam.getOriginUrl());
//        String fullShortUrl;
//        // 为什么说布隆过滤器性能远胜于分布式锁？详情查看：https://nageoffer.com/shortlink/question
//        RLock lock = redissonClient.getLock(SHORT_LINK_CREATE_LOCK_KEY);
//        lock.lock();
//        try {
//            String shortLinkSuffix = generateSuffixByLock(requestParam);
//            fullShortUrl = StrBuilder.create(createShortLinkDefaultDomain)
//                    .append("/")
//                    .append(shortLinkSuffix)
//                    .toString();
//            ShortLinkDO shortLinkDO = ShortLinkDO.builder()
//                    .domain(createShortLinkDefaultDomain)
//                    .originUrl(requestParam.getOriginUrl())
//                    .gid(requestParam.getGid())
//                    .createdType(requestParam.getCreatedType())
//                    .validDateType(requestParam.getValidDateType())
//                    .validDate(requestParam.getValidDate())
//                    .describe(requestParam.getDescribe())
//                    .shortUri(shortLinkSuffix)
//                    .enableStatus(0)
//                    .totalPv(0)
//                    .totalUv(0)
//                    .totalUip(0)
//                    .delTime(0L)
//                    .fullShortUrl(fullShortUrl)
//                    .favicon(getFavicon(requestParam.getOriginUrl()))
//                    .build();
//            ShortLinkGotoDO linkGotoDO = ShortLinkGotoDO.builder()
//                    .fullShortUrl(fullShortUrl)
//                    .gid(requestParam.getGid())
//                    .build();
//            try {
//                baseMapper.insert(shortLinkDO);
//                shortLinkGotoMapper.insert(linkGotoDO);
//            } catch (DuplicateKeyException ex) {
//                throw new ServiceException(String.format("短链接：%s 生成重复", fullShortUrl));
//            }
//            stringRedisTemplate.opsForValue().set(
//                    String.format(GOTO_SHORT_LINK_KEY, fullShortUrl),
//                    requestParam.getOriginUrl(),
//                    LinkUtil.getLinkCacheValidTime(requestParam.getValidDate()), TimeUnit.MILLISECONDS
//            );
//        } finally {
//            lock.unlock();
//        }
//        return ShortLinkCreateRespDTO.builder()
//                .fullShortUrl("http://" + fullShortUrl)
//                .originUrl(requestParam.getOriginUrl())
//                .gid(requestParam.getGid())
//                .build();
//    }
//
    @Override
    public ShortLinkBatchCreateRespDTO batchCreateShortLink(ShortLinkBatchCreateReqDTO requestParam) {
        List<String> originUrls = requestParam.getOriginUrls();
        List<String> describes = requestParam.getDescribes();
        List<ShortLinkBaseInfoRespDTO> result = new ArrayList<>();
        for (int i = 0; i < originUrls.size(); i++) {
            ShortLinkCreateReqDTO shortLinkCreateReqDTO = BeanUtil.toBean(requestParam, ShortLinkCreateReqDTO.class);
            shortLinkCreateReqDTO.setOriginUrl(originUrls.get(i));
            shortLinkCreateReqDTO.setDescribe(describes.get(i));
            try {
                ShortLinkCreateRespDTO shortLink = createShortLink(shortLinkCreateReqDTO);
                ShortLinkBaseInfoRespDTO linkBaseInfoRespDTO = ShortLinkBaseInfoRespDTO.builder()
                        .fullShortUrl(shortLink.getFullShortUrl())
                        .originUrl(shortLink.getOriginUrl())
                        .describe(describes.get(i))
                        .build();
                result.add(linkBaseInfoRespDTO);
            } catch (Throwable ex) {
                log.error("批量创建短链接失败，原始参数：{}", originUrls.get(i));
            }
        }
        return ShortLinkBatchCreateRespDTO.builder()
                .total(result.size())
                .baseLinkInfos(result)
                .build();
    }


//    /**
//     *
//     * @param requestParam 修改短链接请求参数
//     */
//    @Transactional(rollbackFor = Exception.class)
//    @Override
//    public void updateShortLink(ShortLinkUpdateReqDTO requestParam) {
//        LambdaQueryWrapper<ShortLinkDO> queryWrapper = Wrappers.lambdaQuery(ShortLinkDO.class)
//                .eq(ShortLinkDO::getGid, requestParam.getGid())
//                .eq(ShortLinkDO::getFullShortUrl, requestParam.getFullShortUrl())
//                .eq(ShortLinkDO::getDelFlag, 0)
//                .eq(ShortLinkDO::getEnableStatus, 0);
//        ShortLinkDO hasShortLinkDO = baseMapper.selectOne(queryWrapper);
//        if (hasShortLinkDO == null) {
//            throw new ClientException("短链接记录不存在");
//        }
//        ShortLinkDO shortLinkDO = ShortLinkDO.builder()
//                .domain(hasShortLinkDO.getDomain())
//                .shortUri(hasShortLinkDO.getShortUri())
//                .favicon(hasShortLinkDO.getFavicon())
//                .createdType(hasShortLinkDO.getCreatedType())
//                .gid(requestParam.getGid())
//                .originUrl(requestParam.getOriginUrl())
//                .describe(requestParam.getDescribe())
//                .validDateType(requestParam.getValidDateType())
//                .validDate(requestParam.getValidDate())
//                .build();
//        if (Objects.equals(hasShortLinkDO.getGid(), requestParam.getGid())) {
//            LambdaUpdateWrapper<ShortLinkDO> updateWrapper = Wrappers.lambdaUpdate(ShortLinkDO.class)
//                    .eq(ShortLinkDO::getFullShortUrl, requestParam.getFullShortUrl())
//                    .eq(ShortLinkDO::getGid, requestParam.getGid())
//                    .eq(ShortLinkDO::getDelFlag, 0)
//                    .eq(ShortLinkDO::getEnableStatus, 0)
//                    .set(Objects.equals(requestParam.getValidDateType(), VailDateTypeEnum.PERMANENT.getType()), ShortLinkDO::getValidDate, null);
//            baseMapper.update(shortLinkDO, updateWrapper);
//        } else {
//            LambdaUpdateWrapper<ShortLinkDO> updateWrapper = Wrappers.lambdaUpdate(ShortLinkDO.class)
//                    .eq(ShortLinkDO::getFullShortUrl, requestParam.getFullShortUrl())
//                    .eq(ShortLinkDO::getGid, requestParam.getGid())
//                    .eq(ShortLinkDO::getDelFlag, 0)
//                    .eq(ShortLinkDO::getEnableStatus, 0);
//            baseMapper.delete(updateWrapper);
//            shortLinkDO.setGid(requestParam.getGid());
//            baseMapper.insert(shortLinkDO);
//        }
//        // 短链接如何保障缓存和数据库一致性？详情查看：https://nageoffer.com/shortlink/question
//        if (!Objects.equals(hasShortLinkDO.getValidDateType(), requestParam.getValidDateType())
//                || !Objects.equals(hasShortLinkDO.getValidDate(), requestParam.getValidDate())) {
//            stringRedisTemplate.delete(String.format(GOTO_SHORT_LINK_KEY, requestParam.getFullShortUrl()));
//            if (hasShortLinkDO.getValidDate() != null && hasShortLinkDO.getValidDate().before(new Date())) {
//                if (Objects.equals(requestParam.getValidDateType(), VailDateTypeEnum.PERMANENT.getType()) || requestParam.getValidDate().after(new Date())) {
//                    stringRedisTemplate.delete(String.format(GOTO_IS_NULL_SHORT_LINK_KEY, requestParam.getFullShortUrl()));
//                }
//            }
//        }
//    }
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void updateShortLink(ShortLinkUpdateReqDTO requestParam) {
        verificationWhitelist(requestParam.getOriginUrl());
        LambdaQueryWrapper<ShortLinkDO> queryWrapper = Wrappers.lambdaQuery(ShortLinkDO.class)
                .eq(ShortLinkDO::getGid, requestParam.getOriginGid())
                .eq(ShortLinkDO::getFullShortUrl, requestParam.getFullShortUrl())
                .eq(ShortLinkDO::getDelFlag, 0)
                .eq(ShortLinkDO::getEnableStatus, 0);
        ShortLinkDO hasShortLinkDO = baseMapper.selectOne(queryWrapper);
        if (hasShortLinkDO == null) {
            throw new ClientException("短链接记录不存在");
        }
        if (Objects.equals(hasShortLinkDO.getGid(), requestParam.getGid())) {
            LambdaUpdateWrapper<ShortLinkDO> updateWrapper = Wrappers.lambdaUpdate(ShortLinkDO.class)
                    .eq(ShortLinkDO::getFullShortUrl, requestParam.getFullShortUrl())
                    .eq(ShortLinkDO::getGid, requestParam.getGid())
                    .eq(ShortLinkDO::getDelFlag, 0)
                    .eq(ShortLinkDO::getEnableStatus, 0)
                    .set(Objects.equals(requestParam.getValidDateType(), VailDateTypeEnum.PERMANENT.getType()), ShortLinkDO::getValidDate, null);
            ShortLinkDO shortLinkDO = ShortLinkDO.builder()
                    .domain(hasShortLinkDO.getDomain())
                    .shortUri(hasShortLinkDO.getShortUri())
                    .favicon(hasShortLinkDO.getFavicon())
                    .createdType(hasShortLinkDO.getCreatedType())
                    .gid(requestParam.getGid())
                    .originUrl(requestParam.getOriginUrl())
                    .describe(requestParam.getDescribe())
                    .validDateType(requestParam.getValidDateType())
                    .validDate(requestParam.getValidDate())
                    .build();
            baseMapper.update(shortLinkDO, updateWrapper);
        } else {
            // 为什么监控表要加上Gid？不加的话是否就不存在读写锁？详情查看：https://nageoffer.com/shortlink/question
            RReadWriteLock readWriteLock = redissonClient.getReadWriteLock(String.format(LOCK_GID_UPDATE_KEY, requestParam.getFullShortUrl()));
            RLock rLock = readWriteLock.writeLock();
            if (!rLock.tryLock()) {
                throw new ServiceException("短链接正在被访问，请稍后再试...");
            }
            try {
                LambdaUpdateWrapper<ShortLinkDO> linkUpdateWrapper = Wrappers.lambdaUpdate(ShortLinkDO.class)
                        .eq(ShortLinkDO::getFullShortUrl, requestParam.getFullShortUrl())
                        .eq(ShortLinkDO::getGid, hasShortLinkDO.getGid())
                        .eq(ShortLinkDO::getDelFlag, 0)
                        .eq(ShortLinkDO::getDelTime, 0L)
                        .eq(ShortLinkDO::getEnableStatus, 0);
                ShortLinkDO delShortLinkDO = ShortLinkDO.builder()
                        .delTime(System.currentTimeMillis())
                        .build();
                delShortLinkDO.setDelFlag(1);
                baseMapper.update(delShortLinkDO, linkUpdateWrapper);
                ShortLinkDO shortLinkDO = ShortLinkDO.builder()
                        .domain(createShortLinkDefaultDomain)
                        .originUrl(requestParam.getOriginUrl())
                        .gid(requestParam.getGid())
                        .createdType(hasShortLinkDO.getCreatedType())
                        .validDateType(requestParam.getValidDateType())
                        .validDate(requestParam.getValidDate())
                        .describe(requestParam.getDescribe())
                        .shortUri(hasShortLinkDO.getShortUri())
                        .enableStatus(hasShortLinkDO.getEnableStatus())
                        .totalPv(hasShortLinkDO.getTotalPv())
                        .totalUv(hasShortLinkDO.getTotalUv())
                        .totalUip(hasShortLinkDO.getTotalUip())
                        .fullShortUrl(hasShortLinkDO.getFullShortUrl())
                        .favicon(getFavicon(requestParam.getOriginUrl()))
                        .delTime(0L)
                        .build();
                baseMapper.insert(shortLinkDO);
                LambdaQueryWrapper<LinkStatsTodayDO> statsTodayQueryWrapper = Wrappers.lambdaQuery(LinkStatsTodayDO.class)
                        .eq(LinkStatsTodayDO::getFullShortUrl, requestParam.getFullShortUrl())
                        .eq(LinkStatsTodayDO::getGid, hasShortLinkDO.getGid())
                        .eq(LinkStatsTodayDO::getDelFlag, 0);
                List<LinkStatsTodayDO> linkStatsTodayDOList = linkStatsTodayMapper.selectList(statsTodayQueryWrapper);
                if (CollUtil.isNotEmpty(linkStatsTodayDOList)) {
                    linkStatsTodayMapper.deleteBatchIds(linkStatsTodayDOList.stream()
                            .map(LinkStatsTodayDO::getId)
                            .toList()
                    );
                    linkStatsTodayDOList.forEach(each -> each.setGid(requestParam.getGid()));
                    linkStatsTodayService.saveBatch(linkStatsTodayDOList);
                }
                LambdaQueryWrapper<ShortLinkGotoDO> linkGotoQueryWrapper = Wrappers.lambdaQuery(ShortLinkGotoDO.class)
                        .eq(ShortLinkGotoDO::getFullShortUrl, requestParam.getFullShortUrl())
                        .eq(ShortLinkGotoDO::getGid, hasShortLinkDO.getGid());
                ShortLinkGotoDO shortLinkGotoDO = shortLinkGotoMapper.selectOne(linkGotoQueryWrapper);
                shortLinkGotoMapper.deleteById(shortLinkGotoDO.getId());
                shortLinkGotoDO.setGid(requestParam.getGid());
                shortLinkGotoMapper.insert(shortLinkGotoDO);
                LambdaUpdateWrapper<LinkAccessStatsDO> linkAccessStatsUpdateWrapper = Wrappers.lambdaUpdate(LinkAccessStatsDO.class)
                        .eq(LinkAccessStatsDO::getFullShortUrl, requestParam.getFullShortUrl())
                        .eq(LinkAccessStatsDO::getGid, hasShortLinkDO.getGid())
                        .eq(LinkAccessStatsDO::getDelFlag, 0);
                LinkAccessStatsDO linkAccessStatsDO = LinkAccessStatsDO.builder()
                        .gid(requestParam.getGid())
                        .build();
                linkAccessStatsMapper.update(linkAccessStatsDO, linkAccessStatsUpdateWrapper);
                LambdaUpdateWrapper<LinkLocaleStatsDO> linkLocaleStatsUpdateWrapper = Wrappers.lambdaUpdate(LinkLocaleStatsDO.class)
                        .eq(LinkLocaleStatsDO::getFullShortUrl, requestParam.getFullShortUrl())
                        .eq(LinkLocaleStatsDO::getGid, hasShortLinkDO.getGid())
                        .eq(LinkLocaleStatsDO::getDelFlag, 0);
                LinkLocaleStatsDO linkLocaleStatsDO = LinkLocaleStatsDO.builder()
                        .gid(requestParam.getGid())
                        .build();
                linkLocaleStatsMapper.update(linkLocaleStatsDO, linkLocaleStatsUpdateWrapper);
                LambdaUpdateWrapper<LinkOsStatsDO> linkOsStatsUpdateWrapper = Wrappers.lambdaUpdate(LinkOsStatsDO.class)
                        .eq(LinkOsStatsDO::getFullShortUrl, requestParam.getFullShortUrl())
                        .eq(LinkOsStatsDO::getGid, hasShortLinkDO.getGid())
                        .eq(LinkOsStatsDO::getDelFlag, 0);
                LinkOsStatsDO linkOsStatsDO = LinkOsStatsDO.builder()
                        .gid(requestParam.getGid())
                        .build();
                linkOsStatsMapper.update(linkOsStatsDO, linkOsStatsUpdateWrapper);
                LambdaUpdateWrapper<LinkBrowserStatsDO> linkBrowserStatsUpdateWrapper = Wrappers.lambdaUpdate(LinkBrowserStatsDO.class)
                        .eq(LinkBrowserStatsDO::getFullShortUrl, requestParam.getFullShortUrl())
                        .eq(LinkBrowserStatsDO::getGid, hasShortLinkDO.getGid())
                        .eq(LinkBrowserStatsDO::getDelFlag, 0);
                LinkBrowserStatsDO linkBrowserStatsDO = LinkBrowserStatsDO.builder()
                        .gid(requestParam.getGid())
                        .build();
                linkBrowserStatsMapper.update(linkBrowserStatsDO, linkBrowserStatsUpdateWrapper);
                LambdaUpdateWrapper<LinkDeviceStatsDO> linkDeviceStatsUpdateWrapper = Wrappers.lambdaUpdate(LinkDeviceStatsDO.class)
                        .eq(LinkDeviceStatsDO::getFullShortUrl, requestParam.getFullShortUrl())
                        .eq(LinkDeviceStatsDO::getGid, hasShortLinkDO.getGid())
                        .eq(LinkDeviceStatsDO::getDelFlag, 0);
                LinkDeviceStatsDO linkDeviceStatsDO = LinkDeviceStatsDO.builder()
                        .gid(requestParam.getGid())
                        .build();
                linkDeviceStatsMapper.update(linkDeviceStatsDO, linkDeviceStatsUpdateWrapper);
                LambdaUpdateWrapper<LinkNetworkStatsDO> linkNetworkStatsUpdateWrapper = Wrappers.lambdaUpdate(LinkNetworkStatsDO.class)
                        .eq(LinkNetworkStatsDO::getFullShortUrl, requestParam.getFullShortUrl())
                        .eq(LinkNetworkStatsDO::getGid, hasShortLinkDO.getGid())
                        .eq(LinkNetworkStatsDO::getDelFlag, 0);
                LinkNetworkStatsDO linkNetworkStatsDO = LinkNetworkStatsDO.builder()
                        .gid(requestParam.getGid())
                        .build();
                linkNetworkStatsMapper.update(linkNetworkStatsDO, linkNetworkStatsUpdateWrapper);
                LambdaUpdateWrapper<LinkAccessLogsDO> linkAccessLogsUpdateWrapper = Wrappers.lambdaUpdate(LinkAccessLogsDO.class)
                        .eq(LinkAccessLogsDO::getFullShortUrl, requestParam.getFullShortUrl())
                        .eq(LinkAccessLogsDO::getGid, hasShortLinkDO.getGid())
                        .eq(LinkAccessLogsDO::getDelFlag, 0);
                LinkAccessLogsDO linkAccessLogsDO = LinkAccessLogsDO.builder()
                        .gid(requestParam.getGid())
                        .build();
                linkAccessLogsMapper.update(linkAccessLogsDO, linkAccessLogsUpdateWrapper);
            } finally {
                rLock.unlock();
            }
        }
        // 短链接如何保障缓存和数据库一致性？详情查看：https://nageoffer.com/shortlink/question
        if (!Objects.equals(hasShortLinkDO.getValidDateType(), requestParam.getValidDateType())
                || !Objects.equals(hasShortLinkDO.getValidDate(), requestParam.getValidDate())) {
            stringRedisTemplate.delete(String.format(GOTO_SHORT_LINK_KEY, requestParam.getFullShortUrl()));
            if (hasShortLinkDO.getValidDate() != null && hasShortLinkDO.getValidDate().before(new Date())) {
                if (Objects.equals(requestParam.getValidDateType(), VailDateTypeEnum.PERMANENT.getType()) || requestParam.getValidDate().after(new Date())) {
                    stringRedisTemplate.delete(String.format(GOTO_IS_NULL_SHORT_LINK_KEY, requestParam.getFullShortUrl()));
                }
            }
        }
    }


    /**
     *
     * @param requestParam 分页查询短链接请求参数
     * @return
     */
    @Override
    public IPage<ShortLinkPageRespDTO> pageShortLink(ShortLinkPageReqDTO requestParam) {
//        LambdaQueryWrapper<ShortLinkDO> queryWrapper = Wrappers.lambdaQuery(ShortLinkDO.class)
//                .eq(ShortLinkDO::getGid,requestParam.getGid())
//                .eq(ShortLinkDO::getEnableStatus,0)
//                .eq(ShortLinkDO::getDelFlag,0);
//        IPage<ShortLinkDO> resultPage = baseMapper.selectPage(requestParam, queryWrapper);
        IPage<ShortLinkDO> resultPage = baseMapper.pageLink(requestParam);
        return resultPage.convert(each -> {
            ShortLinkPageRespDTO result = BeanUtil.toBean(each, ShortLinkPageRespDTO.class);
            result.setDomain("http://" + result.getDomain());
            return result;
        });
    }

    /**
     *
     * @param requestParam 查询短链接分组内数量请求参数
     * @return
     */
    @Override
    public List<ShortLinkGroupCountQueryRespDTO> listGroupShortLinkCount(List<String> requestParam) {
        QueryWrapper<ShortLinkDO> queryWrapper = Wrappers.query(new ShortLinkDO())
                .select("gid as gid, count(*) as shortLinkCount")
                .in("gid", requestParam)
                .eq("enable_status", 0)
//                .eq("del_flag", 0)
//                .eq("del_time", 0L)
                .groupBy("gid");
        List<Map<String, Object>> shortLinkDOList = baseMapper.selectMaps(queryWrapper);
        return BeanUtil.copyToList(shortLinkDOList, ShortLinkGroupCountQueryRespDTO.class);
    }


    /**
     * 短链接跳转
     * @param shortUri 短链接后缀
     * @param request  HTTP 请求
     * @param response HTTP 响应
     */
    @SneakyThrows
    @Override
    public void restoreUrl(String shortUri, ServletRequest request, ServletResponse response) {
        // 短链接接口的并发量有多少？如何测试？详情查看：https://nageoffer.com/shortlink/question
        // 面试中如何回答短链接是如何跳转长链接？详情查看：https://nageoffer.com/shortlink/question
        String serverName = request.getServerName();
        String serverPort = Optional.of(request.getServerPort())
                .filter(each -> !Objects.equals(each, 80))
                .map(String::valueOf)
                .map(each -> ":" + each)
                .orElse("");
        String fullShortUrl = serverName + serverPort + "/" + shortUri;
        String originalLink = stringRedisTemplate.opsForValue().get(String.format(GOTO_SHORT_LINK_KEY, fullShortUrl));
        if (StrUtil.isNotBlank(originalLink)) {
            ShortLinkStatsRecordDTO statsRecord = buildLinkStatsRecordAndSetUser(fullShortUrl, request, response);
            shortLinkStats(fullShortUrl, null, statsRecord);
//            shortLinkStats(fullShortUrl, null, request,response);
            ((HttpServletResponse) response).sendRedirect(originalLink);
            return;
        }
        boolean contains = shortUriCreateCachePenetrationBloomFilter.contains(fullShortUrl);
        if (!contains) {
            ((HttpServletResponse) response).sendRedirect("/page/notfound");
            return;
        }
        String gotoIsNullShortLink = stringRedisTemplate.opsForValue().get(String.format(GOTO_IS_NULL_SHORT_LINK_KEY, fullShortUrl));
        if (StrUtil.isNotBlank(gotoIsNullShortLink)) {
            ((HttpServletResponse) response).sendRedirect("/page/notfound");
            return;
        }
        RLock lock = redissonClient.getLock(String.format(LOCK_GOTO_SHORT_LINK_KEY, fullShortUrl));
        lock.lock();
        try {
            originalLink = stringRedisTemplate.opsForValue().get(String.format(GOTO_SHORT_LINK_KEY, fullShortUrl));
            if (StrUtil.isNotBlank(originalLink)) {
                ShortLinkStatsRecordDTO statsRecord = buildLinkStatsRecordAndSetUser(fullShortUrl, request, response);
                shortLinkStats(fullShortUrl, null, statsRecord);
//                shortLinkStats(fullShortUrl, null, request,response);
                ((HttpServletResponse) response).sendRedirect(originalLink);
                return;
            }
            LambdaQueryWrapper<ShortLinkGotoDO> linkGotoQueryWrapper = Wrappers.lambdaQuery(ShortLinkGotoDO.class)
                    .eq(ShortLinkGotoDO::getFullShortUrl, fullShortUrl);
            ShortLinkGotoDO shortLinkGotoDO = shortLinkGotoMapper.selectOne(linkGotoQueryWrapper);
            if (shortLinkGotoDO == null) {
                stringRedisTemplate.opsForValue().set(String.format(GOTO_IS_NULL_SHORT_LINK_KEY, fullShortUrl), "-", 30, TimeUnit.MINUTES);
                ((HttpServletResponse) response).sendRedirect("/page/notfound");
                return;
            }
            LambdaQueryWrapper<ShortLinkDO> queryWrapper = Wrappers.lambdaQuery(ShortLinkDO.class)
                    .eq(ShortLinkDO::getGid, shortLinkGotoDO.getGid())
                    .eq(ShortLinkDO::getFullShortUrl, fullShortUrl)
                    .eq(ShortLinkDO::getDelFlag, 0)
                    .eq(ShortLinkDO::getEnableStatus, 0);
            ShortLinkDO shortLinkDO = baseMapper.selectOne(queryWrapper);
            if (shortLinkDO == null || (shortLinkDO.getValidDate() != null && shortLinkDO.getValidDate().before(new Date()))) {
                stringRedisTemplate.opsForValue().set(String.format(GOTO_IS_NULL_SHORT_LINK_KEY, fullShortUrl), "-", 30, TimeUnit.MINUTES);
                ((HttpServletResponse) response).sendRedirect("/page/notfound");
                return;
            }
            stringRedisTemplate.opsForValue().set(
                    String.format(GOTO_SHORT_LINK_KEY, fullShortUrl),
                    shortLinkDO.getOriginUrl(),
                    LinkUtil.getLinkCacheValidTime(shortLinkDO.getValidDate()), TimeUnit.MILLISECONDS
            );
            ShortLinkStatsRecordDTO statsRecord = buildLinkStatsRecordAndSetUser(fullShortUrl, request, response);
            shortLinkStats(fullShortUrl, shortLinkDO.getGid(), statsRecord);
//            shortLinkStats(fullShortUrl, shortLinkDO.getGid(), request,response);
            ((HttpServletResponse) response).sendRedirect(shortLinkDO.getOriginUrl());
        } finally {
            lock.unlock();
        }
    }

    private ShortLinkStatsRecordDTO buildLinkStatsRecordAndSetUser(String fullShortUrl, ServletRequest request, ServletResponse response) {
        AtomicBoolean uvFirstFlag = new AtomicBoolean();
        Cookie[] cookies = ((HttpServletRequest) request).getCookies();
        AtomicReference<String> uv = new AtomicReference<>();
        Runnable addResponseCookieTask = () -> {
            uv.set(UUID.fastUUID().toString());
            Cookie uvCookie = new Cookie("uv", uv.get());
            uvCookie.setMaxAge(60 * 60 * 24 * 30);
            uvCookie.setPath(StrUtil.sub(fullShortUrl, fullShortUrl.indexOf("/"), fullShortUrl.length()));
            ((HttpServletResponse) response).addCookie(uvCookie);
            uvFirstFlag.set(Boolean.TRUE);
            stringRedisTemplate.opsForSet().add(SHORT_LINK_STATS_UV_KEY + fullShortUrl, uv.get());
        };
        if (ArrayUtil.isNotEmpty(cookies)) {
            Arrays.stream(cookies)
                    .filter(each -> Objects.equals(each.getName(), "uv"))
                    .findFirst()
                    .map(Cookie::getValue)
                    .ifPresentOrElse(each -> {
                        uv.set(each);
                        Long uvAdded = stringRedisTemplate.opsForSet().add(SHORT_LINK_STATS_UV_KEY + fullShortUrl, each);
                        uvFirstFlag.set(uvAdded != null && uvAdded > 0L);
                    }, addResponseCookieTask);
        } else {
            addResponseCookieTask.run();
        }
        String remoteAddr = LinkUtil.getActualIp(((HttpServletRequest) request));
        String os = LinkUtil.getOs(((HttpServletRequest) request));
        String browser = LinkUtil.getBrowser(((HttpServletRequest) request));
        String device = LinkUtil.getDevice(((HttpServletRequest) request));
        String network = LinkUtil.getNetwork(((HttpServletRequest) request));
        Long uipAdded = stringRedisTemplate.opsForSet().add(SHORT_LINK_STATS_UIP_KEY + fullShortUrl, remoteAddr);
        boolean uipFirstFlag = uipAdded != null && uipAdded > 0L;
        return ShortLinkStatsRecordDTO.builder()
                .fullShortUrl(fullShortUrl)
                .uv(uv.get())
                .uvFirstFlag(uvFirstFlag.get())
                .uipFirstFlag(uipFirstFlag)
                .remoteAddr(remoteAddr)
                .os(os)
                .browser(browser)
                .device(device)
                .network(network)
                .build();
    }

    @Override
    public void shortLinkStats(String fullShortUrl, String gid, ShortLinkStatsRecordDTO statsRecord) {
        Map<String, String> producerMap = new HashMap<>();
        producerMap.put("fullShortUrl", fullShortUrl);
        producerMap.put("gid", gid);
        producerMap.put("statsRecord", JSON.toJSONString(statsRecord));
        // 消息队列为什么选用RocketMQ？详情查看：https://nageoffer.com/shortlink/question
        shortLinkStatsSaveProducer.send(producerMap);
    }

    /**
     * 短链接监控
//     */
//    private void shortLinkStats(String fullShortUrl,String gid,ServletRequest request,ServletResponse response){
//        AtomicBoolean uvFirstFlag = new AtomicBoolean();
//        Cookie[] cookies = ((HttpServletRequest) request).getCookies();
//        try {
//            AtomicReference<String> uv = new AtomicReference<>();
//            Runnable addResponseCookieTask = () -> {
//                String actualUv = UUID.fastUUID().toString();
//                uv.set(actualUv);
//                Cookie uvCookie = new Cookie("uv",uv.get());
//                uvCookie.setMaxAge(60*60*24*30);
//                uvCookie.setPath(StrUtil.sub(fullShortUrl,fullShortUrl.indexOf("/"),fullShortUrl.length()));
//                ((HttpServletResponse)response).addCookie(uvCookie);
//                stringRedisTemplate.opsForSet().add("short-link:stats:uv" + fullShortUrl, uv.get());
//            };
//            if(ArrayUtil.isNotEmpty(cookies)){
//                Arrays.stream(cookies).filter(each -> Objects.equals(each.getName(),"uv"))
//                        .findFirst().map(Cookie::getValue).ifPresentOrElse(each ->{
//                                    uv.set(each);
//                                    Long uvadd = stringRedisTemplate.opsForSet().add("short-link:stats:uv" + fullShortUrl, each);
//                                    uvFirstFlag.set(uvadd != null && uvadd > 0L);
//                                }, addResponseCookieTask);
//            }else{
//                addResponseCookieTask.run();
//            }
//            String remoteAddr = LinkUtil.getActualIp(((HttpServletRequest) request));
//            Long uipAdded = stringRedisTemplate.opsForSet().add("short-link:stats:uip" + fullShortUrl, remoteAddr);
//            boolean uipFirstFlag = uipAdded != null &&uipAdded > 0L;
//            if(StrUtil.isBlank(gid)){
//                LambdaQueryWrapper<ShortLinkGotoDO> queryWrapper = Wrappers.lambdaQuery(ShortLinkGotoDO.class).eq(ShortLinkGotoDO::getFullShortUrl, fullShortUrl);
//                ShortLinkGotoDO shortLinkGotoDO = shortLinkGotoMapper.selectOne(queryWrapper);
//                gid = shortLinkGotoDO.getGid();
//            }
//            int hour = DateUtil.hour(new Date(), true);
//            Week week = DateUtil.dayOfWeekEnum(new Date());
//            int weekValue = week.getIso8601Value();
//            LinkAccessStatsDO linkAccessStatsDO = LinkAccessStatsDO.builder()
//                    .pv(1).uv(uvFirstFlag.get() ? 1:0).uip(uipFirstFlag ? 1:0).hour(hour).weekday(weekValue).fullShortUrl(fullShortUrl).gid(gid).date(new Date())
//                    .build();
//            linkAccessStatsMapper.shortLinkStats(linkAccessStatsDO);
//            Map<String,Object> localParamMap = new HashMap<>();
//            localParamMap.put("key",statsLocaleAmapKey);
//            localParamMap.put("ip",remoteAddr);
//            String localeResultStr = HttpUtil.get(AMAP_REMOTE_URL, localParamMap);
//            JSONObject localeResultObj = JSON.parseObject(localeResultStr);
//            String infoCode = localeResultObj.getString("infocode");
//            String actualProvince;
//            String actualCity;
//            if(StrUtil.isNotBlank(infoCode)&&StrUtil.equals(infoCode,"10000")) {
//                String province = localeResultObj.getString("province");
//                boolean unknownFlag = StrUtil.isBlank(province);
//                LinkLocaleStatsDO linkLocaleStatsDO = LinkLocaleStatsDO.builder()
//                        .fullShortUrl(fullShortUrl)
//                        .province(actualProvince = unknownFlag ? "未知" : province)
//                        .city(actualCity = unknownFlag ? "未知" : localeResultObj.getString("city"))
//                        .adcode(unknownFlag ? "未知" : localeResultObj.getString("adcode"))
//                        .cnt(1).country("中国")
//                        .gid(gid).date(new Date())
//                        .build();
//                linkLocaleStatsMapper.shortLinkLocaleState(linkLocaleStatsDO);
//                LinkOsStatsDO linkOsStatsDO = LinkOsStatsDO.builder()
//                        .os(LinkUtil.getOs((HttpServletRequest) request))
//                        .cnt(1)
//                        .fullShortUrl(fullShortUrl)
//                        .gid(gid).date(new Date())
//                        .build();
//                linkOsStatsMapper.shortLinkOsState(linkOsStatsDO);
//                LinkBrowserStatsDO linkBrowserStatsDO = LinkBrowserStatsDO.builder()
//                        .browser(LinkUtil.getBrowser((HttpServletRequest) request))
//                        .cnt(1)
//                        .fullShortUrl(fullShortUrl)
//                        .gid(gid).date(new Date())
//                        .build();
//                linkBrowserStatsMapper.shortLinkBrowserState(linkBrowserStatsDO);
//                LinkAccessLogsDO linkAccessLogsDO = LinkAccessLogsDO.builder()
//                        .ip(remoteAddr)
//                        .user(uv.get())
//                        .browser(LinkUtil.getBrowser((HttpServletRequest) request))
//                        .os(LinkUtil.getOs((HttpServletRequest) request))
//                        .fullShortUrl(fullShortUrl)
//                        .gid(gid)
//                        .network(LinkUtil.getNetwork((HttpServletRequest) request))
//                        .locale(StrUtil.join("-","中国",actualProvince,actualCity))
//                        .device(LinkUtil.getDevice((HttpServletRequest) request))
//                        .build();
//                linkAccessLogsMapper.insert(linkAccessLogsDO);
//                LinkDeviceStatsDO linkDeviceStatsDO = LinkDeviceStatsDO.builder()
//                        .device(LinkUtil.getDevice((HttpServletRequest) request))
//                        .cnt(1)
//                        .fullShortUrl(fullShortUrl)
//                        .gid(gid).date(new Date())
//                        .build();
//                linkDeviceStatsMapper.shortLinkDeviceState(linkDeviceStatsDO);
//                LinkNetworkStatsDO linkNetworkStatsDO = LinkNetworkStatsDO.builder()
//                        .network(LinkUtil.getNetwork((HttpServletRequest) request))
//                        .cnt(1)
//                        .fullShortUrl(fullShortUrl)
//                        .gid(gid).date(new Date())
//                        .build();
//                linkNetworkStatsMapper.shortLinkNetworkState(linkNetworkStatsDO);
//                baseMapper.incrementStats(gid,fullShortUrl,1,uvFirstFlag.get() ? 1:0,uipFirstFlag ? 1:0);
//                LinkStatsTodayDO linkStatsTodayDO = LinkStatsTodayDO.builder()
//                        .todayPv(1)
//                        .todayUv(uvFirstFlag.get() ? 1:0)
//                        .todayUip(uipFirstFlag ? 1:0)
//                        .fullShortUrl(fullShortUrl)
//                        .gid(gid).date(new Date())
//                        .build();
//                linkStatsTodayMapper.shortLinkTodayState(linkStatsTodayDO);
//            }
//        }catch(Throwable ex){
//            log.error("短链接访问量统计异常",ex);
//        }
//    }
//
//    private String generateSuffixByLock(ShortLinkCreateReqDTO requestParam) {
//        int customGenerateCount = 0;
//        String shorUri;
//        while (true) {
//            if (customGenerateCount > 10) {
//                throw new ServiceException("短链接频繁生成，请稍后再试");
//            }
//            String originUrl = requestParam.getOriginUrl();
//            originUrl += UUID.randomUUID().toString();
//            // 短链接哈希算法生成冲突问题如何解决？详情查看：https://nageoffer.com/shortlink/question
//            shorUri = HashUtil.hashToBase62(originUrl);
//            LambdaQueryWrapper<ShortLinkDO> queryWrapper = Wrappers.lambdaQuery(ShortLinkDO.class)
//                    .eq(ShortLinkDO::getGid, requestParam.getGid())
//                    .eq(ShortLinkDO::getFullShortUrl, createShortLinkDefaultDomain + "/" + shorUri)
//                    .eq(ShortLinkDO::getDelFlag, 0);
//            ShortLinkDO shortLinkDO = baseMapper.selectOne(queryWrapper);
//            if (shortLinkDO == null) {
//                break;
//            }
//            customGenerateCount++;
//        }
//        return shorUri;
//    }
//

    /**
     * 获取目标网站图标
     * @param url
     * @return
     */
    @SneakyThrows
    private String getFavicon(String url) {
        URL targetUrl = new URL(url);
        HttpURLConnection connection = (HttpURLConnection) targetUrl.openConnection();
        connection.setRequestMethod("GET");
        connection.connect();
        int responseCode = connection.getResponseCode();
        if (HttpURLConnection.HTTP_OK == responseCode) {
            Document document = Jsoup.connect(url).get();
            Element faviconLink = document.select("link[rel~=(?i)^(shortcut )?icon]").first();
            if (faviconLink != null) {
                return faviconLink.attr("abs:href");
            }
        }
        return null;
    }

    private void verificationWhitelist(String originUrl) {
        Boolean enable = gotoDomainWhiteListConfiguration.getEnable();
        if (enable == null || !enable) {
            return;
        }
        String domain = LinkUtil.extractDomain(originUrl);
        if (StrUtil.isBlank(domain)) {
            throw new ClientException("跳转链接填写错误");
        }
        List<String> details = gotoDomainWhiteListConfiguration.getDetails();
        if (!details.contains(domain)) {
            throw new ClientException("演示环境为避免恶意攻击，请生成以下网站跳转链接：" + gotoDomainWhiteListConfiguration.getNames());
        }
    }

}
