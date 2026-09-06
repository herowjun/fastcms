import request from '/@/utils/request';


export function ClientAttachApi() {
	return {
		/**
		 * 附件列表
		 * @param params 
		 * @returns 
		 */
		getAttachList(params: object) {
			return request({
				url: '/client/attachment/list',
				method: 'get',
				params: params
			});
		},

		/**
		 * 上传附件
		 * @param params 
		 * @returns 
		 */
		addAttach(params: object) {
			return request({
				url: '/client/attachment/upload',
				method: 'post',
				data: params,
			});
		},

		/**
		 * 获取附件详情
		 * @param id 
		 * @returns 
		 */
		getAttach(id: string) {
			return request({
				url: '/client/attachment/get/'+id,
				method: 'get'
			});
		},

		/**
		 * 修改附件名称以及描述
		 * @param id 
		 * @param params 
		 * @returns 
		 */
		updateAttach(id:string, params: object) {
			return request({
				url: '/client/attachment/update/'+id,
				method: 'post',
				params: params
			});
		},

		/**
		 * 删除附件
		 * @param id 
		 * @returns 
		 */
		delAttach(id: string) {
			return request({
				url: '/client/attachment/delete/'+id,
				method: 'post'
			});
		},

		/**
		 * 附件目录树（含各目录附件计数，只统计当前用户自己的附件）
		 */
		getDirTree() {
			return request({
				url: '/client/attachment/dir/tree',
				method: 'get'
			});
		},
	};
}