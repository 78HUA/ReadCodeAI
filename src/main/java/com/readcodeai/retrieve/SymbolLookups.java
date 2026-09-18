package com.readcodeai.retrieve;

import com.readcodeai.retrieve.model.SymbolView;

import java.util.List;

/**
 * {@link QueryRouter.SymbolLookup} 的生产实现：把路由需要的那两种查法接到 {@link SymbolQueryService} 上。
 *
 * <p>抽出来是因为**不止一个调用方**要路由（单跳问答、多跳 Agent），
 * 而"怎么查符号"这件事只该有一份 —— 两处各写一遍，改一处忘一处就会让路由行为漂移。
 */
public final class SymbolLookups {

    private SymbolLookups() {
    }

    public static QueryRouter.SymbolLookup of(SymbolQueryService queries, long repoId) {
        return new QueryRouter.SymbolLookup() {
            @Override
            public List<SymbolView> find(String name) {
                // 上限给到 50：`fromJson` 这种名字在真实仓库里有十几个重载，
                // 只取 10 个会把目标截断掉（评估集实测抓出来的）
                return queries.locate(repoId, name, 50);
            }

            @Override
            public List<SymbolView> findInType(String typeQualifiedName, String name) {
                // 限定查找：问题里同时给了类名和方法名时，靠它把范围锁死（裸方法名往往不唯一）
                return queries.findMembersInType(repoId, typeQualifiedName, name);
            }
        };
    }
}
