#import "HRBridgeModule.h"

#import "KuiklyRenderViewController.h"
#import <OpenKuiklyIOSRender/NSObject+KR.h>

#define REQ_PARAM_KEY @"reqParam"
#define CMD_KEY @"cmd"
#define FROM_HIPPY_RENDER @"from_hippy_render"
// 扩展桥接接口
/*
 * @brief Native暴露接口到kotlin侧，提供kotlin侧调用native能力
 */

@implementation HRBridgeModule

@synthesize hr_rootView;

- (void)copyToPasteboard:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] hr_stringToDictionary];
    NSString *content = params[@"content"];
    UIPasteboard *pasteboard = [UIPasteboard generalPasteboard];
    pasteboard.string = content;
}

- (void)showSelectableText:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] hr_stringToDictionary];
    NSString *text = params[@"text"];
    NSString *title = params[@"title"] ?: @"选取文字";
    if (text.length == 0) {
        return;
    }
    // 真可选中文本：Alert 内嵌 UITextView（selectable，左对齐，自动换行，长文内部滚动）
    UIAlertController *alert = [UIAlertController alertControllerWithTitle:title
                                                                  message:nil
                                                           preferredStyle:UIAlertControllerStyleAlert];
    UITextView *textView = [[UITextView alloc] init];
    textView.translatesAutoresizingMaskIntoConstraints = NO;
    textView.editable = NO;
    textView.selectable = YES;
    textView.dataDetectorTypes = UIDataDetectorTypeNone;
    textView.font = [UIFont systemFontOfSize:16];
    textView.text = text;
    textView.textAlignment = NSTextAlignmentLeft;
    textView.backgroundColor = [UIColor clearColor];
    textView.layoutManager.allowsNonContiguousLayout = NO;
    [alert.view addSubview:textView];

    CGFloat screenW = [UIScreen mainScreen].bounds.size.width;
    CGFloat screenH = [UIScreen mainScreen].bounds.size.height;
    [textView.leadingAnchor constraintEqualToAnchor:alert.view.leadingAnchor constant:16].active = YES;
    [textView.trailingAnchor constraintEqualToAnchor:alert.view.trailingAnchor constant:-16].active = YES;
    [textView.topAnchor constraintEqualToAnchor:alert.view.topAnchor constant:50].active = YES;
    [textView.bottomAnchor constraintEqualToAnchor:alert.view.bottomAnchor constant:-50].active = YES;
    [textView.widthAnchor constraintEqualToConstant:screenW - 64].active = YES;
    [textView.heightAnchor constraintLessThanOrEqualToConstant:screenH * 0.6].active = YES;

    [alert addAction:[UIAlertAction actionWithTitle:@"关闭" style:UIAlertActionStyleDefault handler:nil]];
    UIViewController *vc = [UIApplication sharedApplication].keyWindow.rootViewController;
    [vc presentViewController:alert animated:YES completion:nil];
}

- (void)log:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] hr_stringToDictionary];
    NSString *content = params[@"content"];
    NSLog(@"KuiklyRender:%@", content);
}

@end